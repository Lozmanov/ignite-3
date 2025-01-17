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

package org.apache.ignite.internal.index;

import static org.apache.ignite.internal.index.IndexManagementUtils.getPartitionCountFromCatalog;
import static org.apache.ignite.internal.index.IndexManagementUtils.inProgressBuildIndexMetastoreKey;
import static org.apache.ignite.internal.index.IndexManagementUtils.partitionBuildIndexMetastoreKey;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.COLUMN_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.INDEX_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.NODE_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.TABLE_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.assertMetastoreKeyAbsent;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.assertMetastoreKeyPresent;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.awaitTillGlobalMetastoreRevisionIsApplied;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.createIndex;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.createTable;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.indexDescriptor;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.indexId;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.makeIndexAvailable;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willBe;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.apache.ignite.internal.util.ArrayUtils.BYTE_EMPTY_ARRAY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.ignite.internal.catalog.CatalogManager;
import org.apache.ignite.internal.catalog.CatalogTestUtils;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.manager.IgniteComponent;
import org.apache.ignite.internal.metastorage.impl.MetaStorageManagerImpl;
import org.apache.ignite.internal.metastorage.impl.StandaloneMetaStorageManager;
import org.apache.ignite.internal.metastorage.server.KeyValueStorage;
import org.apache.ignite.internal.metastorage.server.TestRocksDbKeyValueStorage;
import org.apache.ignite.internal.testframework.BaseIgniteAbstractTest;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.vault.VaultManager;
import org.apache.ignite.internal.vault.inmemory.InMemoryVaultService;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.ClusterService;
import org.apache.ignite.network.TopologyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** For {@link IndexAvailabilityController} testing on node recovery. */
@ExtendWith(WorkDirectoryExtension.class)
public class IndexAvailabilityControllerRestorerTest extends BaseIgniteAbstractTest {
    @WorkDirectory
    private Path workDir;

    private final HybridClock clock = new HybridClockImpl();

    private final ClusterService clusterService = mock(ClusterService.class);

    private final VaultManager vaultManager = new VaultManager(new InMemoryVaultService());

    private KeyValueStorage keyValueStorage;

    private MetaStorageManagerImpl metaStorageManager;

    private CatalogManager catalogManager;

    private IndexAvailabilityController controller;

    @BeforeEach
    void setUp() throws Exception {
        keyValueStorage = new TestRocksDbKeyValueStorage(NODE_NAME, workDir);

        metaStorageManager = StandaloneMetaStorageManager.create(vaultManager, keyValueStorage);

        catalogManager = CatalogTestUtils.createTestCatalogManager(NODE_NAME, clock, metaStorageManager);

        Stream.of(vaultManager, metaStorageManager, catalogManager).forEach(IgniteComponent::start);

        deployWatches();

        createTable(catalogManager, TABLE_NAME, COLUMN_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAll(
                controller == null ? null : controller::close,
                catalogManager == null ? null : catalogManager::stop,
                metaStorageManager == null ? null : metaStorageManager::stop,
                vaultManager::stop
        );
    }

    @Test
    void testRemoveInProgressBuildIndexMetastoreKeyForAvailableIndexes() throws Exception {
        createIndex(catalogManager, TABLE_NAME, INDEX_NAME + 0, COLUMN_NAME);
        createIndex(catalogManager, TABLE_NAME, INDEX_NAME + 1, COLUMN_NAME);

        int indexId0 = indexId(catalogManager, INDEX_NAME + 0, clock);
        int indexId1 = indexId(catalogManager, INDEX_NAME + 1, clock);

        makeIndexAvailable(catalogManager, indexId0);
        makeIndexAvailable(catalogManager, indexId1);

        // Let's put the inProgressBuildIndexMetastoreKey for only one index in the metastore.
        putInProgressBuildIndexMetastoreKeyInMetastore(indexId0);

        restartComponentsAndPerformRecovery();

        // Let's do checks.
        assertMetastoreKeyAbsent(metaStorageManager, inProgressBuildIndexMetastoreKey(indexId0));
        assertMetastoreKeyAbsent(metaStorageManager, inProgressBuildIndexMetastoreKey(indexId1));

        assertTrue(indexDescriptor(catalogManager, INDEX_NAME + 0, clock).available());
        assertTrue(indexDescriptor(catalogManager, INDEX_NAME + 1, clock).available());
    }

    @Test
    void testMakeIndexAvailableIfNoLeftKeysBuildingIndexForPartitionInMetastore() throws Exception {
        createIndex(catalogManager, TABLE_NAME, INDEX_NAME, COLUMN_NAME);

        int indexId = indexId(catalogManager, INDEX_NAME, clock);

        putInProgressBuildIndexMetastoreKeyInMetastore(indexId);

        restartComponentsAndPerformRecovery();

        // Let's do checks.
        assertMetastoreKeyAbsent(metaStorageManager, inProgressBuildIndexMetastoreKey(indexId));
        assertTrue(indexDescriptor(catalogManager, INDEX_NAME, clock).available());
    }

    @Test
    void testPutIndexBuildKeysForRegisteredIndexes() throws Exception {
        createIndex(catalogManager, TABLE_NAME, INDEX_NAME, COLUMN_NAME);

        int indexId = indexId(catalogManager, INDEX_NAME, clock);

        restartComponentsAndPerformRecovery();

        // Let's do checks.
        assertMetastoreKeyPresent(metaStorageManager, inProgressBuildIndexMetastoreKey(indexId));

        int partitions = getPartitionCountFromCatalog(catalogManager, indexId, catalogManager.latestCatalogVersion());
        assertThat(partitions, greaterThan(0));

        for (int partitionId = 0; partitionId < partitions; partitionId++) {
            assertMetastoreKeyPresent(metaStorageManager, partitionBuildIndexMetastoreKey(indexId, partitionId));
        }

        assertFalse(indexDescriptor(catalogManager, INDEX_NAME, clock).available());
    }

    private void putInProgressBuildIndexMetastoreKeyInMetastore(int indexId) {
        assertThat(metaStorageManager.put(inProgressBuildIndexMetastoreKey(indexId), BYTE_EMPTY_ARRAY), willCompleteSuccessfully());
    }

    private void restartComponentsAndPerformRecovery() throws Exception {
        stopAndRestartComponentsNoDeployWatches();

        recoveryRestorer();

        deployWatches();
    }

    private void stopAndRestartComponentsNoDeployWatches() throws Exception {
        awaitTillGlobalMetastoreRevisionIsApplied(metaStorageManager);

        IgniteUtils.closeAll(
                catalogManager == null ? null : catalogManager::stop,
                metaStorageManager == null ? null : metaStorageManager::stop
        );

        keyValueStorage = new TestRocksDbKeyValueStorage(NODE_NAME, workDir);

        metaStorageManager = StandaloneMetaStorageManager.create(vaultManager, keyValueStorage);

        catalogManager = spy(CatalogTestUtils.createTestCatalogManager(NODE_NAME, clock, metaStorageManager));

        Stream.of(metaStorageManager, catalogManager).forEach(IgniteComponent::start);
    }

    private void deployWatches() throws Exception {
        assertThat(metaStorageManager.deployWatches(), willCompleteSuccessfully());

        awaitTillGlobalMetastoreRevisionIsApplied(metaStorageManager);
    }

    private void recoveryRestorer() {
        if (controller != null) {
            controller.close();
        }

        controller = new IndexAvailabilityController(catalogManager, metaStorageManager, mock(IndexBuilder.class));

        CompletableFuture<Long> metastoreRecoveryFuture = metaStorageManager.recoveryFinishedFuture();

        assertThat(metastoreRecoveryFuture, willBe(greaterThan(0L)));

        controller.recover(metastoreRecoveryFuture.join());
    }

    private void setLocalNodeToClusterService(ClusterNode clusterNode) {
        TopologyService topologyService = mock(TopologyService.class, invocation -> clusterNode);

        when(clusterService.topologyService()).thenReturn(topologyService);
    }
}
