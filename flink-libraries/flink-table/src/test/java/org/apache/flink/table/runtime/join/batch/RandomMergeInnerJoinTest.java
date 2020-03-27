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

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.table.codegen.GeneratedSorter;
import org.apache.flink.table.plan.FlinkJoinRelType;
import org.apache.flink.table.runtime.join.batch.Int2HashJoinOperatorTest.MyProjection;
import org.apache.flink.table.runtime.sort.InMemorySortTest;
import org.apache.flink.table.runtime.sort.NormalizedKeyComputer;
import org.apache.flink.table.runtime.sort.RecordComparator;

import org.codehaus.commons.compiler.CompileException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Collections;

/**
 * Test for merge inner join.
 */
@RunWith(Parameterized.class)
public class RandomMergeInnerJoinTest extends RandomSortMergeInnerJoinTest {

	public RandomMergeInnerJoinTest(boolean leftIsSmall) {
		super(leftIsSmall);
	}

	@Parameterized.Parameters
	public static Collection<Boolean> parameters() {
		return Collections.singletonList(true);
	}

	@Override
	protected StreamOperator getOperator() {
		return new TestMergeJoinOperator(FlinkJoinRelType.INNER);
	}

	/**
	 * Override cookGeneratedClasses.
	 */
	static class TestMergeJoinOperator extends MergeJoinOperator {

		private TestMergeJoinOperator(FlinkJoinRelType type) {
			super(32 * 32 * 1024, 32 * 32 * 1024, type,
				null, null, null,
				new GeneratedSorter(null, null, null, null),
				new boolean[]{true});
		}

		@Override
		protected CookedClasses cookGeneratedClasses(ClassLoader cl) throws CompileException {
			Class<RecordComparator> comparatorClass;
			try {
				Tuple2<NormalizedKeyComputer, RecordComparator> base =
					InMemorySortTest.getIntSortBase(0, true, "RandomMergeInnerJoinTest");
				comparatorClass = (Class<RecordComparator>) base.f1.getClass();
			} catch (Exception e) {
				throw new RuntimeException();
			}
			return new CookedClasses(
				(Class) MyConditionFunction.class,
				comparatorClass,
				(Class) MyProjection.class,
				(Class) MyProjection.class
			);
		}
	}
}
