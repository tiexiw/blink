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

package org.apache.flink.table.resource.batch.parallelism;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.plan.nodes.exec.BatchExecNode;
import org.apache.flink.table.plan.nodes.exec.ExecNode;
import org.apache.flink.table.plan.nodes.physical.batch.BatchExecScan;
import org.apache.flink.table.util.NodeResourceUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Infer result partition count according to statistics.
 */
public class BatchParallelismCalculatorOnStatistics extends BatchShuffleStageParallelismCalculator {
	private final Map<ExecNode<?, ?>, Integer> calculatedResultMap = new HashMap<>();

	public BatchParallelismCalculatorOnStatistics(
			Configuration tableConf,
			int envParallelism) {
		super(tableConf, envParallelism);
	}

	@Override
	protected void calculate(ShuffleStage shuffleStage) {
		if (shuffleStage.isFinalParallelism()) {
			return;
		}
		Set<ExecNode<?, ?>> nodeSet = shuffleStage.getExecNodeSet();
		for (ExecNode<?, ?> node : nodeSet) {
			shuffleStage.setParallelism(calculate(node), false);
		}
	}

	private int calculate(ExecNode<?, ?> execNode) {
		if (calculatedResultMap.containsKey(execNode)) {
			return calculatedResultMap.get(execNode);
		}
		int result;
		if (execNode instanceof BatchExecScan) {
			result = calculateSource((BatchExecScan) execNode);
		} else if (execNode.getInputNodes().size() == 1) {
			result = calculateSingleNode(execNode);
		} else if (execNode.getInputNodes().size() == 2) {
			result = calculateBiNode(execNode);
		} else {
			throw new TableException("could not reach here. " + execNode.getClass());
		}
		calculatedResultMap.put(execNode, result);
		return result;
	}

	private int calculateSingleNode(ExecNode<?, ?> singleNode) {
		double rowCount = ((BatchExecNode) singleNode.getInputNodes().get(0)).getEstimatedRowCount();
		return NodeResourceUtil.calOperatorParallelism(rowCount, getTableConf());
	}

	private int calculateBiNode(ExecNode<?, ?> twoInputNode) {
		double maxRowCount = Math.max(((BatchExecNode) twoInputNode.getInputNodes().get(0)).getEstimatedRowCount(),
				((BatchExecNode) twoInputNode.getInputNodes().get(1)).getEstimatedRowCount());
		return NodeResourceUtil.calOperatorParallelism(maxRowCount, getTableConf());
	}

}
