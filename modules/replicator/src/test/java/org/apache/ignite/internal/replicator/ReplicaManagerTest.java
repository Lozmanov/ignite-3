/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.replicator;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.ignite.internal.replicator.LocalReplicaEvent.AFTER_REPLICA_STARTED;
import static org.apache.ignite.internal.replicator.LocalReplicaEvent.BEFORE_REPLICA_STOPPED;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willBe;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.cluster.management.ClusterManagementGroupManager;
import org.apache.ignite.internal.event.EventListener;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.lang.NodeStoppingException;
import org.apache.ignite.internal.placementdriver.PlacementDriver;
import org.apache.ignite.internal.raft.client.TopologyAwareRaftGroupService;
import org.apache.ignite.internal.replicator.listener.ReplicaListener;
import org.apache.ignite.internal.testframework.BaseIgniteAbstractTest;
import org.apache.ignite.internal.util.PendingComparableValuesTracker;
import org.apache.ignite.network.ClusterNodeImpl;
import org.apache.ignite.network.ClusterService;
import org.apache.ignite.network.MessagingService;
import org.apache.ignite.network.NetworkAddress;
import org.apache.ignite.network.TopologyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link ReplicaManager}.
 */
@ExtendWith(MockitoExtension.class)
public class ReplicaManagerTest extends BaseIgniteAbstractTest {
    private ReplicaManager replicaManager;

    @BeforeEach
    void startReplicaManager(
            TestInfo testInfo,
            @Mock ClusterService clusterService,
            @Mock ClusterManagementGroupManager cmgManager,
            @Mock PlacementDriver placementDriver,
            @Mock MessagingService messagingService,
            @Mock TopologyService topologyService
    ) {
        String nodeName = testNodeName(testInfo, 0);

        when(clusterService.messagingService()).thenReturn(messagingService);
        when(clusterService.topologyService()).thenReturn(topologyService);

        when(topologyService.localMember()).thenReturn(new ClusterNodeImpl(nodeName, nodeName, new NetworkAddress("foo", 0)));

        when(cmgManager.metaStorageNodes()).thenReturn(completedFuture(Set.of()));

        var clock = new HybridClockImpl();

        replicaManager = new ReplicaManager(nodeName, clusterService, cmgManager, clock, Set.of(), placementDriver);

        replicaManager.start();
    }

    @AfterEach
    void stopReplicaManager() throws Exception {
        CompletableFuture<?>[] replicaStopFutures = replicaManager.startedGroups().stream()
            .map(id -> {
                try {
                    return replicaManager.stopReplica(id);
                } catch (NodeStoppingException e) {
                    throw new AssertionError(e);
                }
            })
            .toArray(CompletableFuture[]::new);

        assertThat(allOf(replicaStopFutures), willCompleteSuccessfully());

        replicaManager.stop();
    }

    /**
     * Tests that Replica Manager produces events when a Replica is started or stopped.
     */
    @Test
    void testReplicaEvents(
            @Mock EventListener<LocalReplicaEventParameters> createReplicaListener,
            @Mock EventListener<LocalReplicaEventParameters> removeReplicaListener,
            @Mock ReplicaListener replicaListener,
            @Mock TopologyAwareRaftGroupService raftGroupService
    ) throws NodeStoppingException {
        when(raftGroupService.unsubscribeLeader()).thenReturn(completedFuture(null));

        when(createReplicaListener.notify(any(), any())).thenReturn(completedFuture(false));
        when(removeReplicaListener.notify(any(), any())).thenReturn(completedFuture(false));

        replicaManager.listen(AFTER_REPLICA_STARTED, createReplicaListener);
        replicaManager.listen(BEFORE_REPLICA_STOPPED, removeReplicaListener);

        var groupId = new TablePartitionId(0, 0);

        CompletableFuture<Replica> startReplicaFuture = replicaManager.startReplica(
                groupId,
                completedFuture(null),
                replicaListener,
                raftGroupService,
                new PendingComparableValuesTracker<>(0L)
        );

        assertThat(startReplicaFuture, willCompleteSuccessfully());

        var expectedCreateParams = new LocalReplicaEventParameters(groupId);

        verify(createReplicaListener).notify(eq(expectedCreateParams), isNull());
        verify(removeReplicaListener, never()).notify(any(), any());

        CompletableFuture<Boolean> stopReplicaFuture = replicaManager.stopReplica(groupId);

        assertThat(stopReplicaFuture, willBe(true));

        verify(createReplicaListener).notify(eq(expectedCreateParams), isNull());
        verify(removeReplicaListener).notify(eq(expectedCreateParams), isNull());
    }
}
