/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobmaster.failover;

import org.apache.flink.core.io.InputSplit;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;

import java.util.Map;

/**
 * The base class of the operation log.
 */
public class InputSplitsOperationLog extends OperationLog {

	private final JobVertexID jobVertexID;

	private final Map<OperatorID, InputSplit[]> inputSplitsMap;

	public InputSplitsOperationLog(
			final JobVertexID jobVertexID,
			final Map<OperatorID, InputSplit[]> inputSplitsMap) {
		super(OperationLogType.GRAPH_MANAGER);

		this.jobVertexID = jobVertexID;
		this.inputSplitsMap = inputSplitsMap;
	}

	public JobVertexID getJobVertexID() {
		return jobVertexID;
	}

	public Map<OperatorID, InputSplit[]> getInputSplitsMap() {
		return inputSplitsMap;
	}
}
