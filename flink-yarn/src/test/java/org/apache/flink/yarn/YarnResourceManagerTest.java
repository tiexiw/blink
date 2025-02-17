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

package org.apache.flink.yarn;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ResourceManagerOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.concurrent.ScheduledExecutorServiceAdapter;
import org.apache.flink.runtime.entrypoint.ClusterInformation;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.heartbeat.TestingHeartbeatServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.highavailability.TestingHighAvailabilityServices;
import org.apache.flink.runtime.instance.HardwareDescription;
import org.apache.flink.runtime.leaderelection.TestingLeaderElectionService;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.metrics.NoOpMetricRegistry;
import org.apache.flink.runtime.registration.RegistrationResponse;
import org.apache.flink.runtime.resourcemanager.JobLeaderIdService;
import org.apache.flink.runtime.resourcemanager.ResourceManagerConfiguration;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.resourcemanager.SlotRequest;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.TestingRpcService;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.TaskExecutorRegistrationSuccess;
import org.apache.flink.runtime.testutils.DirectScheduledExecutorService;
import org.apache.flink.runtime.util.TestingFatalErrorHandler;
import org.apache.flink.util.TestLogger;

import org.apache.flink.shaded.guava18.com.google.common.collect.ImmutableList;

import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.NMClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.yarn.YarnConfigKeys.ENV_APP_ID;
import static org.apache.flink.yarn.YarnConfigKeys.ENV_CLIENT_HOME_DIR;
import static org.apache.flink.yarn.YarnConfigKeys.ENV_CLIENT_SHIP_FILES;
import static org.apache.flink.yarn.YarnConfigKeys.ENV_FLINK_CLASSPATH;
import static org.apache.flink.yarn.YarnConfigKeys.ENV_HADOOP_USER_NAME;
import static org.apache.flink.yarn.YarnConfigKeys.FLINK_JAR_PATH;
import static org.apache.flink.yarn.YarnConfigKeys.FLINK_YARN_FILES;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * General tests for the YARN resource manager component.
 */
public class YarnResourceManagerTest extends TestLogger {

	private static final Logger LOG = LoggerFactory.getLogger(YarnResourceManagerTest.class);

	private static final Time TIMEOUT = Time.seconds(10L);

	private Configuration flinkConfig = new Configuration();

	private Map<String, String> env = new HashMap<>();

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void setup() {
		flinkConfig.setInteger(ResourceManagerOptions.CONTAINERIZED_HEAP_CUTOFF_MIN, 100);
		flinkConfig.setLong(TaskManagerOptions.NETWORK_BUFFERS_MEMORY_MAX, 64);
		File root = folder.getRoot();
		File home = new File(root, "home");
		boolean created = home.mkdir();
		assertTrue(created);

		env.put(ENV_APP_ID, "foo");
		env.put(ENV_CLIENT_HOME_DIR, home.getAbsolutePath());
		env.put(ENV_CLIENT_SHIP_FILES, "");
		env.put(ENV_FLINK_CLASSPATH, "");
		env.put(ENV_HADOOP_USER_NAME, "foo");
		env.put(FLINK_JAR_PATH, root.toURI().toString());
	}

	@After
	public void teardown() {
		env.clear();
	}

	static class TestingYarnResourceManager extends YarnResourceManager {
		public AMRMClientAsync<AMRMClient.ContainerRequest> mockResourceManagerClient;
		public NMClient mockNMClient;

		public TestingYarnResourceManager(
				RpcService rpcService,
				String resourceManagerEndpointId,
				ResourceID resourceId,
				Configuration flinkConfig,
				Map<String, String> env,
				ResourceManagerConfiguration resourceManagerConfiguration,
				HighAvailabilityServices highAvailabilityServices,
				HeartbeatServices heartbeatServices,
				SlotManager slotManager,
				MetricRegistry metricRegistry,
				JobLeaderIdService jobLeaderIdService,
				ClusterInformation clusterInformation,
				FatalErrorHandler fatalErrorHandler,
				@Nullable String webInterfaceUrl,
				AMRMClientAsync<AMRMClient.ContainerRequest> mockResourceManagerClient,
				NMClient mockNMClient) {
			super(
				rpcService,
				resourceManagerEndpointId,
				resourceId,
				flinkConfig,
				env,
				resourceManagerConfiguration,
				highAvailabilityServices,
				heartbeatServices,
				slotManager,
				metricRegistry,
				jobLeaderIdService,
				clusterInformation,
				fatalErrorHandler,
				webInterfaceUrl);
			this.mockNMClient = mockNMClient;
			this.mockResourceManagerClient = mockResourceManagerClient;
		}

		public <T> CompletableFuture<T> runInMainThread(Callable<T> callable) {
			return callAsync(callable, TIMEOUT);
		}

		public MainThreadExecutor getMainThreadExecutorForTesting() {
			return super.getMainThreadExecutor();
		}

		@Override
		protected AMRMClientAsync<AMRMClient.ContainerRequest> createAndStartResourceManagerClient(
				YarnConfiguration yarnConfiguration,
				int yarnHeartbeatIntervalMillis,
				@Nullable String webInteraceUrl) {
			return mockResourceManagerClient;
		}

		@Override
		protected NMClient createAndStartNodeManagerClient(YarnConfiguration yarnConfiguration) {
			return mockNMClient;
		}

		@Override
		protected void runAsync(final Runnable runnable) {
			runnable.run();
		}

	}

	class Context {

		// services
		final TestingRpcService rpcService;
		final TestingFatalErrorHandler fatalErrorHandler;
		final MockResourceManagerRuntimeServices rmServices;

		// RM
		final ResourceManagerConfiguration rmConfiguration;
		final ResourceID rmResourceID;
		static final String RM_ADDRESS = "resourceManager";
		final TestingYarnResourceManager resourceManager;

		final int dataPort = 1234;
		final HardwareDescription hardwareDescription = new HardwareDescription(1, 2L, 3L, 4L);

		// domain objects for test purposes
		final ResourceProfile resourceProfile1 = new ResourceProfile(1.0, 200);
		final ResourceProfile resourceProfile2 = new ResourceProfile(2.0, 300);

		public ContainerId task = ContainerId.newInstance(
				ApplicationAttemptId.newInstance(ApplicationId.newInstance(1L, 0), 0), 1);
		public String taskHost = "host1";

		public NMClient mockNMClient = mock(NMClient.class);
		public AMRMClientAsync<AMRMClient.ContainerRequest> mockResourceManagerClient =
				mock(AMRMClientAsync.class);

		/**
		 * Create mock RM dependencies.
		 */
		Context() throws Exception {
			rpcService = new TestingRpcService();
			fatalErrorHandler = new TestingFatalErrorHandler();
			rmServices = new MockResourceManagerRuntimeServices();

			// resource manager
			rmConfiguration = new ResourceManagerConfiguration(
					Time.seconds(5L),
					Time.seconds(5L));
			rmResourceID = ResourceID.generate();
			resourceManager =
					new TestingYarnResourceManager(
							rpcService,
							RM_ADDRESS,
							rmResourceID,
							flinkConfig,
							env,
							rmConfiguration,
							rmServices.highAvailabilityServices,
							rmServices.heartbeatServices,
							rmServices.slotManager,
							rmServices.metricRegistry,
							rmServices.jobLeaderIdService,
							new ClusterInformation("localhost", 1234),
							fatalErrorHandler,
							null,
							mockResourceManagerClient,
							mockNMClient);
		}

		/**
		 * Mock services needed by the resource manager.
		 */
		class MockResourceManagerRuntimeServices {

			public final ScheduledExecutor scheduledExecutor;
			public final TestingHighAvailabilityServices highAvailabilityServices;
			public final HeartbeatServices heartbeatServices;
			public final MetricRegistry metricRegistry;
			public final TestingLeaderElectionService rmLeaderElectionService;
			public final JobLeaderIdService jobLeaderIdService;
			public final SlotManager slotManager;

			public UUID rmLeaderSessionId;

			MockResourceManagerRuntimeServices() throws Exception {
				scheduledExecutor = mock(ScheduledExecutor.class);
				highAvailabilityServices = new TestingHighAvailabilityServices();
				rmLeaderElectionService = new TestingLeaderElectionService();
				highAvailabilityServices.setResourceManagerLeaderElectionService(rmLeaderElectionService);
				heartbeatServices = new TestingHeartbeatServices(5L, 5L, scheduledExecutor);
				metricRegistry = NoOpMetricRegistry.INSTANCE;
				slotManager = new SlotManager(
						new ScheduledExecutorServiceAdapter(new DirectScheduledExecutorService()),
						Time.seconds(10), Time.seconds(10), Time.minutes(1));
				jobLeaderIdService = new JobLeaderIdService(
						highAvailabilityServices,
						rpcService.getScheduledExecutor(),
						Time.minutes(5L));
			}

			public void grantLeadership() throws Exception {
				rmLeaderSessionId = UUID.randomUUID();
				rmLeaderElectionService.isLeader(rmLeaderSessionId).get(TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS);
			}
		}

		/**
		 * Start the resource manager and grant leadership to it.
		 */
		public void startResourceManager() throws Exception {
			resourceManager.start();
			rmServices.grantLeadership();
		}

		/**
		 * Stop the Akka actor system.
		 */
		public void stopResourceManager() throws Exception {
			rpcService.stopService().get();
		}

		public void waitForThreadPoolExecution() throws Exception {
			ThreadPoolExecutor executor = (ThreadPoolExecutor) resourceManager.executor;
			while (!(executor.getQueue().size() == 0 && executor.getActiveCount() == 0)) {
				LOG.info("Waiting for all launch container requests been executed.");
				Thread.sleep(500);
			}
		}
	}

	@Test
	public void testStopWorker() throws Exception {
		new Context() {{
			startResourceManager();
			// Request slot from SlotManager.
			CompletableFuture<?> registerSlotRequestFuture = resourceManager.runInMainThread(() -> {
				rmServices.slotManager.registerSlotRequest(
					new SlotRequest(new JobID(), new AllocationID(), resourceProfile1, taskHost));
				return null;
			});

			// wait for the registerSlotRequest completion
			registerSlotRequestFuture.get();

			// Callback from YARN when container is allocated.
			Container testingContainer = mock(Container.class);
			when(testingContainer.getId()).thenReturn(
				ContainerId.newInstance(
					ApplicationAttemptId.newInstance(
						ApplicationId.newInstance(System.currentTimeMillis(), 1),
						1),
					1));
			when(testingContainer.getNodeId()).thenReturn(NodeId.newInstance("container", 1234));
			when(testingContainer.getResource()).thenReturn(Resource.newInstance(200, 1));
			when(testingContainer.getPriority()).thenReturn(Priority.newInstance(0));
			resourceManager.onContainersAllocated(ImmutableList.of(testingContainer));
			waitForThreadPoolExecution();
			verify(mockResourceManagerClient).addContainerRequest(any(AMRMClient.ContainerRequest.class));
			verify(mockNMClient).startContainer(eq(testingContainer), any(ContainerLaunchContext.class));

			// Remote task executor registers with YarnResourceManager.
			TaskExecutorGateway mockTaskExecutorGateway = mock(TaskExecutorGateway.class);
			rpcService.registerGateway(taskHost, mockTaskExecutorGateway);

			final ResourceManagerGateway rmGateway = resourceManager.getSelfGateway(ResourceManagerGateway.class);

			final ResourceID taskManagerResourceId = new ResourceID(testingContainer.getId().toString());
			final SlotReport slotReport = new SlotReport(
				new SlotStatus(
					new SlotID(taskManagerResourceId, 1),
					new ResourceProfile(10, 1, 1, 1, 0, Collections.emptyMap())));

			CompletableFuture<Integer> numberRegisteredSlotsFuture = rmGateway
				.registerTaskExecutor(
					taskHost,
					taskManagerResourceId,
					dataPort,
					hardwareDescription,
					Time.seconds(10L))
				.thenCompose(
					(RegistrationResponse response) -> {
						assertThat(response, instanceOf(TaskExecutorRegistrationSuccess.class));
						final TaskExecutorRegistrationSuccess success = (TaskExecutorRegistrationSuccess) response;
						return rmGateway.sendSlotReport(
							taskManagerResourceId,
							success.getRegistrationId(),
							slotReport,
							Time.seconds(10L));
					})
				.handleAsync(
					(Acknowledge ignored, Throwable throwable) -> rmServices.slotManager.getNumberRegisteredSlots(),
					resourceManager.getMainThreadExecutorForTesting());

			final int numberRegisteredSlots = numberRegisteredSlotsFuture.get();

			assertEquals(1, numberRegisteredSlots);

			// Unregister all task executors and release all containers.
			CompletableFuture<?> unregisterAndReleaseFuture =  resourceManager.runInMainThread(() -> {
				rmServices.slotManager.unregisterTaskManagersAndReleaseResources();
				return null;
			});

			unregisterAndReleaseFuture.get();

			verify(mockResourceManagerClient).releaseAssignedContainer(any(ContainerId.class));

			stopResourceManager();

			// It's now safe to access the SlotManager state since the ResourceManager has been stopped.
			assertTrue(rmServices.slotManager.getNumberRegisteredSlots() == 0);
			assertTrue(resourceManager.getNumberOfRegisteredTaskManagers().get() == 0);
		}};
	}

	/**
	 * Tests that application files are deleted when the YARN application master is de-registered.
	 */
	@Test
	public void testDeleteApplicationFiles() throws Exception {
		new Context() {{
			final File applicationDir = folder.newFolder(".flink");
			env.put(FLINK_YARN_FILES, applicationDir.getCanonicalPath());

			startResourceManager();

			resourceManager.deregisterApplication(ApplicationStatus.SUCCEEDED, null);
			assertFalse("YARN application directory was not removed", Files.exists(applicationDir.toPath()));
		}};
	}

	/**
	 * Tests that YarnResourceManager will not request more containers than needs during
	 * callback from Yarn when container is Completed.
	 * @throws Exception
	 */
	@Test
	public void testOnContainerCompleted() throws Exception {
		new Context() {{
			startResourceManager();
			CompletableFuture<?> registerSlotRequestFuture = resourceManager.runInMainThread(() -> {
				rmServices.slotManager.registerSlotRequest(
					new SlotRequest(new JobID(), new AllocationID(), resourceProfile1, taskHost));
				return null;
			});

			CompletableFuture<?> registerSlotRequestFuture2 = resourceManager.runInMainThread(() -> {
				rmServices.slotManager.registerSlotRequest(
					new SlotRequest(new JobID(), new AllocationID(), resourceProfile2, taskHost));
				return null;
			});

			// wait for the registerSlotRequest completion
			registerSlotRequestFuture.get();
			registerSlotRequestFuture2.get();

			ContainerId testContainerId = ContainerId.newInstance(
				ApplicationAttemptId.newInstance(
					ApplicationId.newInstance(System.currentTimeMillis(), 1),
					1),
				1);

			// Callback from YARN when container is allocated.
			Container testingContainer = mock(Container.class);
			when(testingContainer.getId()).thenReturn(testContainerId);
			when(testingContainer.getNodeId()).thenReturn(NodeId.newInstance("container", 1234));
			when(testingContainer.getResource()).thenReturn(Resource.newInstance(200, 1));
			when(testingContainer.getPriority()).thenReturn(Priority.newInstance(0));
			resourceManager.onContainersAllocated(ImmutableList.of(testingContainer));
			waitForThreadPoolExecution();
			verify(mockResourceManagerClient, times(2)).addContainerRequest(any(AMRMClient.ContainerRequest.class));
			verify(mockNMClient).startContainer(eq(testingContainer), any(ContainerLaunchContext.class));

			ContainerId testContainerId2 = ContainerId.newInstance(
				ApplicationAttemptId.newInstance(
					ApplicationId.newInstance(System.currentTimeMillis(), 1),
					1),
				1);

			// Callback from YARN when container is allocated.
			Container testingContainer2 = mock(Container.class);
			when(testingContainer2.getId()).thenReturn(testContainerId2);
			when(testingContainer2.getNodeId()).thenReturn(NodeId.newInstance("container2", 1234));
			when(testingContainer2.getResource()).thenReturn(Resource.newInstance(300, 2));
			when(testingContainer2.getPriority()).thenReturn(Priority.newInstance(1));
			resourceManager.onContainersAllocated(ImmutableList.of(testingContainer2));
			waitForThreadPoolExecution();
			verify(mockResourceManagerClient, times(2)).addContainerRequest(any(AMRMClient.ContainerRequest.class));
			verify(mockNMClient).startContainer(eq(testingContainer), any(ContainerLaunchContext.class));

			// Callback from YARN when container is Completed, pending request can not be fulfilled by pending
			// containers, need to request new container.
			ContainerStatus testingContainerStatus = mock(ContainerStatus.class);
			when(testingContainerStatus.getContainerId()).thenReturn(testContainerId);
			when(testingContainerStatus.getState()).thenReturn(ContainerState.COMPLETE);
			when(testingContainerStatus.getDiagnostics()).thenReturn("Test exit");
			when(testingContainerStatus.getExitStatus()).thenReturn(-1);
			resourceManager.onContainersCompleted(ImmutableList.of(testingContainerStatus));
			verify(mockResourceManagerClient, times(3)).addContainerRequest(any(AMRMClient.ContainerRequest.class));

			// Callback from YARN when container is Completed happened before global fail, pending request
			// slot is already fulfilled by pending containers, no need to request new container.
			when(testingContainerStatus.getContainerId()).thenReturn(testContainerId);
			when(testingContainerStatus.getState()).thenReturn(ContainerState.COMPLETE);
			when(testingContainerStatus.getDiagnostics()).thenReturn("Test exit");
			when(testingContainerStatus.getExitStatus()).thenReturn(-1);
			resourceManager.onContainersCompleted(ImmutableList.of(testingContainerStatus));
			verify(mockResourceManagerClient, times(3)).addContainerRequest(any(AMRMClient.ContainerRequest.class));

			// Callback from YARN when container is Completed, pending request can not be fulfilled by pending
			// containers, need to request new container.
			ContainerStatus testingContainerStatus2 = mock(ContainerStatus.class);
			when(testingContainerStatus2.getContainerId()).thenReturn(testContainerId2);
			when(testingContainerStatus2.getState()).thenReturn(ContainerState.COMPLETE);
			when(testingContainerStatus2.getDiagnostics()).thenReturn("Test exit");
			when(testingContainerStatus2.getExitStatus()).thenReturn(-1);
			resourceManager.onContainersCompleted(ImmutableList.of(testingContainerStatus2));
			verify(mockResourceManagerClient, times(4)).addContainerRequest(any(AMRMClient.ContainerRequest.class));

			// Callback from YARN when container is Completed happened before global fail, pending request
			// slot is already fulfilled by pending containers, no need to request new container.
			when(testingContainerStatus2.getContainerId()).thenReturn(testContainerId2);
			when(testingContainerStatus2.getState()).thenReturn(ContainerState.COMPLETE);
			when(testingContainerStatus2.getDiagnostics()).thenReturn("Test exit");
			when(testingContainerStatus2.getExitStatus()).thenReturn(-1);
			resourceManager.onContainersCompleted(ImmutableList.of(testingContainerStatus2));
			verify(mockResourceManagerClient, times(4)).addContainerRequest(any(AMRMClient.ContainerRequest.class));
		}};
	}

	@Test
	public void testHeartbeatTimeoutWithTaskExecutor() throws Exception {
		new Context() {{
			startResourceManager();
			// Request slot from SlotManager.
			CompletableFuture<?> registerSlotRequestFuture = resourceManager.runInMainThread(() -> {
				rmServices.slotManager.registerSlotRequest(
						new SlotRequest(new JobID(), new AllocationID(), resourceProfile1, taskHost));
				return null;
			});

			// wait for the registerSlotRequest completion
			registerSlotRequestFuture.get();

			// Callback from YARN when container is allocated.
			Container testingContainer = mock(Container.class);
			when(testingContainer.getId()).thenReturn(
					ContainerId.newInstance(
							ApplicationAttemptId.newInstance(
									ApplicationId.newInstance(System.currentTimeMillis(), 1),
									1),
							1));
			when(testingContainer.getNodeId()).thenReturn(NodeId.newInstance("container", 1234));
			when(testingContainer.getResource()).thenReturn(Resource.newInstance(200, 1));
			when(testingContainer.getPriority()).thenReturn(Priority.newInstance(0));
			resourceManager.onContainersAllocated(ImmutableList.of(testingContainer));
			waitForThreadPoolExecution();
			verify(mockResourceManagerClient).addContainerRequest(any(AMRMClient.ContainerRequest.class));
			verify(mockNMClient).startContainer(eq(testingContainer), any(ContainerLaunchContext.class));

			// Remote task executor registers with YarnResourceManager.
			TaskExecutorGateway mockTaskExecutorGateway = mock(TaskExecutorGateway.class);
			rpcService.registerGateway(taskHost, mockTaskExecutorGateway);

			final ResourceManagerGateway rmGateway = resourceManager.getSelfGateway(ResourceManagerGateway.class);

			final ResourceID taskManagerResourceId = new ResourceID(testingContainer.getId().toString());
			final SlotReport slotReport = new SlotReport(
					new SlotStatus(
							new SlotID(taskManagerResourceId, 1),
							new ResourceProfile(10, 1, 1, 1, 0, Collections.emptyMap())));

			CompletableFuture<Integer> numberRegisteredSlotsFuture = rmGateway
					.registerTaskExecutor(
							taskHost,
							taskManagerResourceId,
							dataPort,
							hardwareDescription,
							Time.seconds(10L))
					.thenCompose(
							(RegistrationResponse response) -> {
								assertThat(response, instanceOf(TaskExecutorRegistrationSuccess.class));
								final TaskExecutorRegistrationSuccess success = (TaskExecutorRegistrationSuccess) response;
								return rmGateway.sendSlotReport(
										taskManagerResourceId,
										success.getRegistrationId(),
										slotReport,
										Time.seconds(10L));
							})
					.handleAsync(
							(Acknowledge ignored, Throwable throwable) -> rmServices.slotManager.getNumberRegisteredSlots(),
							resourceManager.getMainThreadExecutorForTesting());

			final int numberRegisteredSlots = numberRegisteredSlotsFuture.get();

			assertEquals(1, numberRegisteredSlots);

			ArgumentCaptor<Runnable> heartbeatRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
			verify(rmServices.scheduledExecutor, times(2)).scheduleAtFixedRate(
					heartbeatRunnableCaptor.capture(),
					eq(0L),
					eq(5L),
					eq(TimeUnit.MILLISECONDS));

			List<Runnable> heartbeatRunnable = heartbeatRunnableCaptor.getAllValues();

			ArgumentCaptor<Runnable> timeoutRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
			verify(rmServices.scheduledExecutor).schedule(timeoutRunnableCaptor.capture(), eq(5L), eq(TimeUnit.MILLISECONDS));

			Runnable timeoutRunnable = timeoutRunnableCaptor.getValue();

			// Run all the heartbeat requests.
			for (Runnable runnable : heartbeatRunnable) {
				runnable.run();
			}

			verify(mockTaskExecutorGateway, times(1)).heartbeatFromResourceManager(eq(rmResourceID));

			// Run the timeout runnable to simulate a heartbeat timeout.
			timeoutRunnable.run();

			verify(mockTaskExecutorGateway, Mockito.timeout(Time.seconds(10L).toMilliseconds()))
					.disconnectResourceManager(any(Exception.class));

			// Stop worker should be called.
			verify(mockResourceManagerClient, times(1)).releaseAssignedContainer(any());

			// Unregister all task executors and release all containers.
			CompletableFuture<?> unregisterAndReleaseFuture = resourceManager.runInMainThread(() -> {
				rmServices.slotManager.unregisterTaskManagersAndReleaseResources();
				return null;
			});

			unregisterAndReleaseFuture.get();

			verify(mockResourceManagerClient).releaseAssignedContainer(any(ContainerId.class));

			stopResourceManager();

			// It's now safe to access the SlotManager state since the ResourceManager has been stopped.
			assertTrue(rmServices.slotManager.getNumberRegisteredSlots() == 0);
			assertTrue(resourceManager.getNumberOfRegisteredTaskManagers().get() == 0);
		}};
	}
}
