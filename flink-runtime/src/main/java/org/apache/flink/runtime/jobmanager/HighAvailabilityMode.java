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

package org.apache.flink.runtime.jobmanager;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;

/**
 * High availability mode for Flink's cluster execution. Currently supported modes are:
 *
 * - NONE: No high availability.
 * - FILESYSTEM: JobManager stores job graphs, checkpoints, etc to file system which can be
 * accessed after JobManager failovers.
 * - ZooKeeper: JobManager high availability via ZooKeeper
 * ZooKeeper is used to select a leader among a group of JobManager. This JobManager
 * is responsible for the job execution. Upon failure of the leader a new leader is elected
 * which will take over the responsibilities of the old leader
 */
public enum HighAvailabilityMode {
	NONE,
	FILESYSTEM,
	ZOOKEEPER;

	/**
	 * Return the configured {@link HighAvailabilityMode}.
	 *
	 * @param config The config to parse
	 * @return Configured recovery mode or {@link HighAvailabilityMode#NONE} if not
	 * configured.
	 */
	public static HighAvailabilityMode fromConfig(Configuration config) {
		String haMode = config.getValue(HighAvailabilityOptions.HA_MODE);

		if (haMode == null) {
			return HighAvailabilityMode.NONE;
		} else if (haMode.equalsIgnoreCase(ConfigConstants.DEFAULT_RECOVERY_MODE)) {
			// Map old default to new default
			return HighAvailabilityMode.NONE;
		} else {
			return HighAvailabilityMode.valueOf(haMode.toUpperCase());
		}
	}

	/**
	 * Returns true if the defined recovery mode supports high availability.
	 *
	 * @param configuration Configuration which contains the recovery mode
	 * @return true if high availability is supported by the recovery mode, otherwise false
	 */
	public static boolean isHighAvailabilityModeActivated(Configuration configuration) {
		HighAvailabilityMode mode = fromConfig(configuration);
		switch (mode) {
			case NONE:
				return false;
			case FILESYSTEM:
			case ZOOKEEPER:
				return true;
			default:
				return false;
		}

	}
}
