/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.join.batch;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeComparator;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.base.IntComparator;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.runtime.TupleComparator;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.operators.sort.MergeIterator;
import org.apache.flink.runtime.operators.testutils.Match;
import org.apache.flink.runtime.operators.testutils.TestData;
import org.apache.flink.runtime.operators.testutils.TestData.TupleGenerator;
import org.apache.flink.runtime.operators.testutils.TestData.TupleGenerator.KeyMode;
import org.apache.flink.runtime.operators.testutils.TestData.TupleGenerator.ValueMode;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.TwoInputStreamTask;
import org.apache.flink.streaming.runtime.tasks.TwoInputStreamTaskTestHarness;
import org.apache.flink.table.codegen.GeneratedSorter;
import org.apache.flink.table.codegen.JoinConditionFunction;
import org.apache.flink.table.dataformat.BaseRow;
import org.apache.flink.table.dataformat.BinaryRow;
import org.apache.flink.table.dataformat.BinaryRowWriter;
import org.apache.flink.table.dataformat.JoinedRow;
import org.apache.flink.table.plan.FlinkJoinRelType;
import org.apache.flink.table.runtime.join.batch.Int2HashJoinOperatorTest.MyProjection;
import org.apache.flink.table.runtime.sort.InMemorySortTest;
import org.apache.flink.table.runtime.sort.NormalizedKeyComputer;
import org.apache.flink.table.runtime.sort.RecordComparator;
import org.apache.flink.table.typeutils.BaseRowTypeInfo;
import org.apache.flink.util.MutableObjectIterator;

import org.codehaus.commons.compiler.CompileException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.apache.flink.api.common.typeinfo.BasicTypeInfo.INT_TYPE_INFO;
import static org.apache.flink.api.common.typeinfo.BasicTypeInfo.STRING_TYPE_INFO;

/**
 * Test for sort merge inner join.
 */
@RunWith(Parameterized.class)
public class RandomSortMergeInnerJoinTest {

	private static final long SEED1 = 561349061987311L;
	private static final long SEED2 = 231434613412342L;
	private static final int INPUT_FIRST_SIZE = 20000;
	private static final int INPUT_SECOND_SIZE = 1000;

	private boolean leftIsSmall;
	private TypeComparator<Tuple2<Integer, String>> comparator1;
	private TypeComparator<Tuple2<Integer, String>> comparator2;

	public RandomSortMergeInnerJoinTest(boolean leftIsSmall) {
		this.leftIsSmall = leftIsSmall;
	}

	@Parameterized.Parameters
	public static Collection<Boolean> parameters() {
		return Arrays.asList(true, false);
	}

	@Before
	public void before() {
		comparator1 = new TupleComparator<>(
				new int[]{0},
				new TypeComparator<?>[]{new IntComparator(true)},
				new TypeSerializer<?>[]{IntSerializer.INSTANCE});
		comparator2 = new TupleComparator<>(
				new int[]{0},
				new TypeComparator<?>[]{new IntComparator(true)},
				new TypeSerializer<?>[]{IntSerializer.INSTANCE});
	}

	@Test
	public void test() throws Exception {
		final TupleGenerator generator1 =
				new TupleGenerator(SEED1, 500, 4096, KeyMode.SORTED, ValueMode.RANDOM_LENGTH);
		final TupleGenerator generator2 =
				new TupleGenerator(SEED2, 500, 2048, KeyMode.SORTED, ValueMode.RANDOM_LENGTH);

		final TestData.TupleGeneratorIterator input1 = new TestData.TupleGeneratorIterator(generator1, INPUT_FIRST_SIZE);
		final TestData.TupleGeneratorIterator input2 = new TestData.TupleGeneratorIterator(generator2, INPUT_SECOND_SIZE);

		// collect expected data
		final Map<Integer, Collection<Match>> expectedMatchesMap = matchValues(
				collectData(input1), collectData(input2));

		// reset the generators
		generator1.reset();
		generator2.reset();
		input1.reset();
		input2.reset();

		StreamOperator operator = getOperator();

		match(expectedMatchesMap, transformToBinary(join(operator, input1, input2)));

		// assert that each expected match was seen
		for (Map.Entry<Integer, Collection<Match>> entry : expectedMatchesMap.entrySet()) {
			Assert.assertTrue("Collection for key " + entry.getKey() + " is not empty", entry.getValue().isEmpty());
		}
	}

	@Test
	public void testMergeWithHighNumberOfCommonKeys() {
		// the size of the left and right inputs
		final int input1Size = 200;
		final int input2Size = 100;

		final int input1Duplicates = 10;
		final int input2Duplicates = 4000;
		final int duplicateKey = 13;

		try {
			final TupleGenerator generator1 = new TupleGenerator(SEED1, 500, 4096, KeyMode.SORTED, ValueMode.RANDOM_LENGTH);
			final TupleGenerator generator2 = new TupleGenerator(SEED2, 500, 2048, KeyMode.SORTED, ValueMode.RANDOM_LENGTH);

			final TestData.TupleGeneratorIterator gen1Iter = new TestData.TupleGeneratorIterator(generator1, input1Size);
			final TestData.TupleGeneratorIterator gen2Iter = new TestData.TupleGeneratorIterator(generator2, input2Size);

			final TestData.TupleConstantValueIterator const1Iter = new TestData.TupleConstantValueIterator(duplicateKey, "LEFT String for Duplicate Keys", input1Duplicates);
			final TestData.TupleConstantValueIterator const2Iter = new TestData.TupleConstantValueIterator(duplicateKey, "RIGHT String for Duplicate Keys", input2Duplicates);

			final List<MutableObjectIterator<Tuple2<Integer, String>>> inList1 = new ArrayList<>();
			inList1.add(gen1Iter);
			inList1.add(const1Iter);

			final List<MutableObjectIterator<Tuple2<Integer, String>>> inList2 = new ArrayList<>();
			inList2.add(gen2Iter);
			inList2.add(const2Iter);

			MutableObjectIterator<Tuple2<Integer, String>> input1 = new MergeIterator<>(inList1, comparator1.duplicate());
			MutableObjectIterator<Tuple2<Integer, String>> input2 = new MergeIterator<>(inList2, comparator2.duplicate());

			// collect expected data
			final Map<Integer, Collection<Match>> expectedMatchesMap = matchValues(
					collectData(input1),
					collectData(input2));

			// re-create the whole thing for actual processing

			// reset the generators and iterators
			generator1.reset();
			generator2.reset();
			const1Iter.reset();
			const2Iter.reset();
			gen1Iter.reset();
			gen2Iter.reset();

			inList1.clear();
			inList1.add(gen1Iter);
			inList1.add(const1Iter);

			inList2.clear();
			inList2.add(gen2Iter);
			inList2.add(const2Iter);

			input1 = new MergeIterator<>(inList1, comparator1.duplicate());
			input2 = new MergeIterator<>(inList2, comparator2.duplicate());

			StreamOperator operator = getOperator();

			match(expectedMatchesMap, transformToBinary(join(operator, input1, input2)));

			// assert that each expected match was seen
			for (Map.Entry<Integer, Collection<Match>> entry : expectedMatchesMap.entrySet()) {
				if (!entry.getValue().isEmpty()) {
					Assert.fail("Collection for key " + entry.getKey() + " is not empty");
				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail("An exception occurred during the test: " + e.getMessage());
		}
	}

	public static void match(Map<Integer, Collection<Match>> expectedMatchesMap,
			LinkedBlockingQueue<Object> values) {
		for (Object o : values) {
			BinaryRow row = ((StreamRecord<BinaryRow>) o).getValue();

			final Integer key = row.getInt(0);
			final String value1 = row.isNullAt(1) ? null : row.getString(1);
			final String value2 = row.isNullAt(2) ? null : row.getString(2);

			Collection<Match> matches = expectedMatchesMap.get(key);
			if (matches == null) {
				Assert.fail("Match " + key + " - " + value1 + ":" + value2 + " is unexpected.");
			}

			boolean contained = matches.remove(new Match(value1, value2));
			if (!contained) {
				Assert.fail("Produced match was not contained: " + key + " - " + value1 + ":" + value2 +
						", now have: " + matches);
			}
			if (matches.isEmpty()) {
				expectedMatchesMap.remove(key);
			}
		}
	}

	public static LinkedBlockingQueue<Object> join(
			StreamOperator operator,
			MutableObjectIterator<Tuple2<Integer, String>> input1,
			MutableObjectIterator<Tuple2<Integer, String>> input2) throws Exception {
		return join(operator, input1, input2, true);
	}

	public static LinkedBlockingQueue<Object> join(
			StreamOperator operator,
			MutableObjectIterator<Tuple2<Integer, String>> input1,
			MutableObjectIterator<Tuple2<Integer, String>> input2,
			boolean input1First) throws Exception {
		BaseRowTypeInfo typeInfo = new BaseRowTypeInfo(INT_TYPE_INFO, STRING_TYPE_INFO);
		BaseRowTypeInfo joinedInfo = new BaseRowTypeInfo(
				INT_TYPE_INFO, STRING_TYPE_INFO, INT_TYPE_INFO, STRING_TYPE_INFO);
		final TwoInputStreamTaskTestHarness<BinaryRow, BinaryRow, JoinedRow> testHarness =
			new TwoInputStreamTaskTestHarness<>(
				TwoInputStreamTask::new, 2, 1, new int[]{1, 2}, typeInfo, (TypeInformation) typeInfo,
				joinedInfo);

		// Deep pit!!! Cause in TwoInputStreamTaskTestHarness, one record one buffer.
		testHarness.bufferSize = 10 * 1024;

		testHarness.getExecutionConfig().enableObjectReuse();

		testHarness.memorySize = 36 * 1024 * 1024;
		testHarness.setupOutputForSingletonOperatorChain();
		testHarness.getStreamConfig().setStreamOperator(operator);
		testHarness.getStreamConfig().setOperatorID(new OperatorID());

		long initialTime = 0L;

		testHarness.invoke();
		testHarness.waitForTaskRunning();

		if (input1First) {
			Tuple2<Integer, String> tuple2 = new Tuple2<>();
			while ((tuple2 = input1.next(tuple2)) != null) {
				testHarness.processElement(new StreamRecord<>(newRow(tuple2.f0, tuple2.f1), initialTime), 0, 0);
			}
			testHarness.endInput(0);

			tuple2 = new Tuple2<>();
			while ((tuple2 = input2.next(tuple2)) != null) {
				testHarness.processElement(new StreamRecord<>(newRow(tuple2.f0, tuple2.f1), initialTime), 1, 0);
			}
			testHarness.endInput(1);
		} else {
			Tuple2<Integer, String> tuple2 = new Tuple2<>();
			while ((tuple2 = input2.next(tuple2)) != null) {
				testHarness.processElement(new StreamRecord<>(newRow(tuple2.f0, tuple2.f1), initialTime), 1, 0);
			}
			testHarness.endInput(1);

			tuple2 = new Tuple2<>();
			while ((tuple2 = input1.next(tuple2)) != null) {
				testHarness.processElement(new StreamRecord<>(newRow(tuple2.f0, tuple2.f1), initialTime), 0, 0);
			}
			testHarness.endInput(0);
		}

		testHarness.waitForTaskCompletion();
		return testHarness.getOutput();
	}

	public static BinaryRow newRow(int i, String s1) {
		BinaryRow row = new BinaryRow(2);
		BinaryRowWriter writer = new BinaryRowWriter(row);
		writer.writeInt(0, i);
		writer.writeString(1, s1);
		writer.complete();
		return row;
	}

	public static Map<Integer, Collection<Match>> matchValues(
			Map<Integer, Collection<String>> leftMap,
			Map<Integer, Collection<String>> rightMap) {
		Map<Integer, Collection<Match>> map = new HashMap<>();

		for (Integer key : leftMap.keySet()) {
			Collection<String> leftValues = leftMap.get(key);
			Collection<String> rightValues = rightMap.get(key);

			if (rightValues == null) {
				continue;
			}

			if (!map.containsKey(key)) {
				map.put(key, new ArrayList<>());
			}

			Collection<Match> matchedValues = map.get(key);

			for (String leftValue : leftValues) {
				for (String rightValue : rightValues) {
					matchedValues.add(new Match(leftValue, rightValue));
				}
			}
		}

		return map;
	}

	public static Map<Integer, Collection<String>> collectData(MutableObjectIterator<Tuple2<Integer, String>> iter)
			throws Exception {
		final Map<Integer, Collection<String>> map = new HashMap<>();
		Tuple2<Integer, String> pair = new Tuple2<>();

		while ((pair = iter.next(pair)) != null) {
			final Integer key = pair.getField(0);

			if (!map.containsKey(key)) {
				map.put(key, new ArrayList<>());
			}

			Collection<String> values = map.get(key);
			final String value = pair.getField(1);
			values.add(value);
		}

		return map;
	}

	protected StreamOperator getOperator() {
		return new TestSortMergeJoinOperator(FlinkJoinRelType.INNER, leftIsSmall);
	}

	/**
	 * Override cookGeneratedClasses.
	 */
	static class TestSortMergeJoinOperator extends SortMergeJoinOperator {

		public TestSortMergeJoinOperator(FlinkJoinRelType type, boolean leftIsSmaller) {
			super(32 * 32 * 1024, 32 * 32 * 1024, 0, 1024 * 1024, type, leftIsSmaller,
					null, null, null,
					new GeneratedSorter(null, null, null, null),
					new GeneratedSorter(null, null, null, null),
					new GeneratedSorter(null, null, null, null),
					new boolean[]{true});
		}

		@Override
		protected CookedClasses cookGeneratedClasses(ClassLoader cl) throws CompileException {
			Class<NormalizedKeyComputer> computerClass;
			Class<RecordComparator> comparatorClass;
			try {
				Tuple2<NormalizedKeyComputer, RecordComparator> base =
						InMemorySortTest.getIntStringSortBase(true, "RandomSortMergeInnerJoinTest");
				computerClass = (Class<NormalizedKeyComputer>) base.f0.getClass();
				comparatorClass = (Class<RecordComparator>) base.f1.getClass();
			} catch (Exception e) {
				throw new RuntimeException();
			}
			return new CookedClasses(
					(Class) MyConditionFunction.class,
					comparatorClass,
					(Class) MyProjection.class,
					(Class) MyProjection.class,
					computerClass,
					computerClass,
					comparatorClass,
					comparatorClass
			);
		}
	}

	/**
	 * my join condition function.
	 */
	public static final class MyConditionFunction extends JoinConditionFunction {
		@Override
		public boolean apply(BaseRow in1, BaseRow in2) {
			return true;
		}
	}

	public static LinkedBlockingQueue<Object> transformToBinary(LinkedBlockingQueue<Object> output) {
		LinkedBlockingQueue<Object> ret = new LinkedBlockingQueue<>();
		for (Object o : output) {
			BaseRow row = ((StreamRecord<BaseRow>) o).getValue();
			BinaryRow binaryRow;
			if (row.isNullAt(0)) {
				binaryRow = newRow(row.getInt(2), null, row.getString(3));
			} else if (row.isNullAt(2)) {
				binaryRow = newRow(row.getInt(0), row.getString(1), null);
			} else {
				String value1 = row.getString(1);
				String value2 = row.getString(3);
				binaryRow = newRow(row.getInt(0), value1, value2);
			}
			ret.add(new StreamRecord(binaryRow));
		}
		return ret;
	}

	public static BinaryRow newRow(int i, String s1, String s2) {
		BinaryRow row = new BinaryRow(3);
		BinaryRowWriter writer = new BinaryRowWriter(row);
		writer.writeInt(0, i);
		if (s1 == null) {
			writer.setNullAt(1);
		} else {
			writer.writeString(1, s1);
		}
		if (s2 == null) {
			writer.setNullAt(2);
		} else {
			writer.writeString(2, s2);
		}
		writer.complete();
		return row;
	}

}
