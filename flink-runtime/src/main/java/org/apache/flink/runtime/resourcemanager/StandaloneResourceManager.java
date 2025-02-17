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

package org.apache.flink.runtime.resourcemanager;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.standalone.TaskManagerResourceCalculator;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.TaskManagerResource;
import org.apache.flink.runtime.entrypoint.ClusterInformation;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.slotmanager.DynamicAssigningSlotManager;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcService;

import javax.annotation.Nullable;

/**
 * A standalone implementation of the resource manager. Used when the system is started in
 * standalone mode (via scripts), rather than via a resource framework like YARN or Mesos.
 *
 * <p>This ResourceManager doesn't acquire new resources.
 */
public class StandaloneResourceManager extends ResourceManager<ResourceID> {

	private final TaskManagerResource taskManagerResource;

	public StandaloneResourceManager(
			RpcService rpcService,
			String resourceManagerEndpointId,
			ResourceID resourceId,
			ResourceManagerConfiguration resourceManagerConfiguration,
			HighAvailabilityServices highAvailabilityServices,
			HeartbeatServices heartbeatServices,
			SlotManager slotManager,
			MetricRegistry metricRegistry,
			JobLeaderIdService jobLeaderIdService,
			ClusterInformation clusterInformation,
			FatalErrorHandler fatalErrorHandler) {
		this(
			rpcService,
			resourceManagerEndpointId,
			resourceId,
			new Configuration(),
			resourceManagerConfiguration,
			highAvailabilityServices,
			heartbeatServices,
			slotManager,
			metricRegistry,
			jobLeaderIdService,
			clusterInformation,
			fatalErrorHandler);
	}

	public StandaloneResourceManager(
			RpcService rpcService,
			String resourceManagerEndpointId,
			ResourceID resourceId,
			Configuration configuration,
			ResourceManagerConfiguration resourceManagerConfiguration,
			HighAvailabilityServices highAvailabilityServices,
			HeartbeatServices heartbeatServices,
			SlotManager slotManager,
			MetricRegistry metricRegistry,
			JobLeaderIdService jobLeaderIdService,
			ClusterInformation clusterInformation,
			FatalErrorHandler fatalErrorHandler) {
		super(
			rpcService,
			resourceManagerEndpointId,
			resourceId,
			resourceManagerConfiguration,
			highAvailabilityServices,
			heartbeatServices,
			slotManager,
			metricRegistry,
			jobLeaderIdService,
			clusterInformation,
			fatalErrorHandler);

		// build the task manager's total resource according to user's resource
		taskManagerResource =
				TaskManagerResource.fromConfiguration(configuration,
						TaskManagerResourceCalculator.initContainerResourceConfig(configuration), 1);
		log.info(taskManagerResource.toString());

		if (slotManager instanceof DynamicAssigningSlotManager) {
			((DynamicAssigningSlotManager) slotManager).setTotalResourceOfTaskExecutor(
				taskManagerResource.getTaskResourceProfile());
			log.info("TaskExecutors should be started with JVM heap size {} MB, " +
							"new generation size {} MB, JVM direct memory limit {} MB",
					taskManagerResource.getTotalHeapMemory(),
					taskManagerResource.getYoungHeapMemory(),
					taskManagerResource.getTotalDirectMemory());
		} else {
			log.warn("DynamicAssigningSlotManager have not been set in StandaloneResourceManager, " +
					"setResources() of operator may not work!");
		}
	}

	@Override
	protected void initialize() throws ResourceManagerException {
		// nothing to initialize
	}

	@Override
	protected void internalDeregisterApplication(ApplicationStatus finalStatus, @Nullable String diagnostics) {
	}

	@Override
	public void startNewWorker(ResourceProfile resourceProfile) {
	}

	@Override
	public boolean stopWorker(ResourceID resourceID) {
		// standalone resource manager cannot stop workers
		return false;
	}

	@Override
	public void cancelNewWorker(ResourceProfile resourceProfile) {
	}

	@Override
	protected int getNumberAllocatedWorkers() {
		return 0;
	}

	@Override
	protected ResourceID workerStarted(ResourceID resourceID) {
		return resourceID;
	}

}
