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

package org.apache.ignite.internal.tx.impl;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.ignite.internal.hlc.HybridTimestamp.hybridTimestampToLong;
import static org.apache.ignite.internal.replicator.ReplicaManager.DEFAULT_IDLE_SAFE_TIME_PROPAGATION_PERIOD_MILLISECONDS;
import static org.apache.ignite.internal.tx.TxState.ABORTED;
import static org.apache.ignite.internal.tx.TxState.COMMITED;
import static org.apache.ignite.internal.tx.TxState.PENDING;
import static org.apache.ignite.internal.tx.TxState.checkTransitionCorrectness;
import static org.apache.ignite.internal.tx.TxState.isFinalState;
import static org.apache.ignite.internal.util.ExceptionUtils.withCause;
import static org.apache.ignite.internal.util.IgniteUtils.inBusyLockAsync;
import static org.apache.ignite.internal.util.IgniteUtils.shutdownAndAwaitTermination;
import static org.apache.ignite.lang.ErrorGroups.Replicator.REPLICA_UNAVAILABLE_ERR;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_READ_ONLY_TOO_OLD_ERR;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_WAS_ABORTED_ERR;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.lang.IgniteInternalException;
import org.apache.ignite.internal.lang.IgniteStringFormatter;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.placementdriver.PlacementDriver;
import org.apache.ignite.internal.placementdriver.ReplicaMeta;
import org.apache.ignite.internal.replicator.ReplicaService;
import org.apache.ignite.internal.replicator.ReplicationGroupId;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.replicator.exception.PrimaryReplicaMissException;
import org.apache.ignite.internal.replicator.exception.ReplicationTimeoutException;
import org.apache.ignite.internal.replicator.message.ErrorReplicaResponse;
import org.apache.ignite.internal.replicator.message.ReplicaMessageGroup;
import org.apache.ignite.internal.replicator.message.ReplicaResponse;
import org.apache.ignite.internal.thread.NamedThreadFactory;
import org.apache.ignite.internal.tx.HybridTimestampTracker;
import org.apache.ignite.internal.tx.InternalTransaction;
import org.apache.ignite.internal.tx.LockManager;
import org.apache.ignite.internal.tx.TransactionMeta;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.TxState;
import org.apache.ignite.internal.tx.TxStateMeta;
import org.apache.ignite.internal.tx.TxStateMetaFinishing;
import org.apache.ignite.internal.tx.message.TxFinishReplicaRequest;
import org.apache.ignite.internal.tx.message.TxMessagesFactory;
import org.apache.ignite.internal.util.ExceptionUtils;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;
import org.apache.ignite.internal.util.Lazy;
import org.apache.ignite.network.NetworkMessage;
import org.apache.ignite.network.NetworkMessageHandler;
import org.apache.ignite.tx.TransactionException;
import org.jetbrains.annotations.Nullable;

/**
 * A transaction manager implementation.
 *
 * <p>Uses 2PC for atomic commitment and 2PL for concurrency control.
 */
public class TxManagerImpl implements TxManager, NetworkMessageHandler {
    /** The logger. */
    private static final IgniteLogger LOG = Loggers.forClass(TxManagerImpl.class);

    /** Hint for maximum concurrent txns. */
    private static final int MAX_CONCURRENT_TXNS = 1024;

    private static final int AWAIT_PRIMARY_REPLICA_TIMEOUT = 10;

    /** Tx messages factory. */
    private static final TxMessagesFactory FACTORY = new TxMessagesFactory();

    private final ReplicaService replicaService;

    /** Lock manager. */
    private final LockManager lockManager;

    /** Executor that runs async transaction cleanup actions. */
    private final ExecutorService cleanupExecutor;

    /** A hybrid logical clock. */
    private final HybridClock clock;

    /** Generates transaction IDs. */
    private final TransactionIdGenerator transactionIdGenerator;

    /** The local map for tx states. */
    private final ConcurrentHashMap<UUID, TxStateMeta> txStateMap = new ConcurrentHashMap<>();

    /** Txn contexts. */
    private final ConcurrentHashMap<UUID, TxContext> txCtxMap = new ConcurrentHashMap<>(MAX_CONCURRENT_TXNS);

    /** Future of a read-only transaction by it {@link TxIdAndTimestamp}. */
    private final ConcurrentNavigableMap<TxIdAndTimestamp, CompletableFuture<Void>> readOnlyTxFutureById = new ConcurrentSkipListMap<>(
            Comparator.comparing(TxIdAndTimestamp::getReadTimestamp).thenComparing(TxIdAndTimestamp::getTxId)
    );

    /**
     * Low watermark, does not allow creating read-only transactions less than or equal to this value, {@code null} means it has never been
     * updated yet.
     */
    private final AtomicReference<HybridTimestamp> lowWatermark = new AtomicReference<>();

    /** Lock to update and read the low watermark. */
    private final ReadWriteLock lowWatermarkReadWriteLock = new ReentrantReadWriteLock();

    private final Lazy<String> localNodeId;

    private final PlacementDriver placementDriver;

    private final LongSupplier idleSafeTimePropagationPeriodMsSupplier;

    /** Prevents double stopping of the tracker. */
    private final AtomicBoolean stopGuard = new AtomicBoolean();

    /** Busy lock to stop synchronously. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    /**
     * The constructor.
     *
     * @param replicaService Replica service.
     * @param lockManager Lock manager.
     * @param clock A hybrid logical clock.
     * @param transactionIdGenerator Used to generate transaction IDs.
     */
    public TxManagerImpl(
            ReplicaService replicaService,
            LockManager lockManager,
            HybridClock clock,
            TransactionIdGenerator transactionIdGenerator,
            Supplier<String> localNodeIdSupplier,
            PlacementDriver placementDriver
    ) {
        this(
                replicaService,
                lockManager,
                clock,
                transactionIdGenerator,
                localNodeIdSupplier,
                placementDriver,
                () -> DEFAULT_IDLE_SAFE_TIME_PROPAGATION_PERIOD_MILLISECONDS
        );
    }

    /**
     * The constructor.
     *
     * @param replicaService Replica service.
     * @param lockManager Lock manager.
     * @param clock A hybrid logical clock.
     * @param transactionIdGenerator Used to generate transaction IDs.
     * @param idleSafeTimePropagationPeriodMsSupplier Used to get idle safe time propagation period in ms.
     */
    public TxManagerImpl(
            ReplicaService replicaService,
            LockManager lockManager,
            HybridClock clock,
            TransactionIdGenerator transactionIdGenerator,
            Supplier<String> localNodeIdSupplier,
            PlacementDriver placementDriver,
            LongSupplier idleSafeTimePropagationPeriodMsSupplier
    ) {
        this.replicaService = replicaService;
        this.lockManager = lockManager;
        this.clock = clock;
        this.transactionIdGenerator = transactionIdGenerator;
        this.localNodeId = new Lazy<>(localNodeIdSupplier);
        this.placementDriver = placementDriver;
        this.idleSafeTimePropagationPeriodMsSupplier = idleSafeTimePropagationPeriodMsSupplier;

        int cpus = Runtime.getRuntime().availableProcessors();

        cleanupExecutor = new ThreadPoolExecutor(
                cpus,
                cpus,
                100,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("tx-async-cleanup", LOG));
    }

    @Override
    public InternalTransaction begin(HybridTimestampTracker timestampTracker) {
        return begin(timestampTracker, false);
    }

    @Override
    public InternalTransaction begin(HybridTimestampTracker timestampTracker, boolean readOnly) {
        HybridTimestamp beginTimestamp = clock.now();
        UUID txId = transactionIdGenerator.transactionIdFor(beginTimestamp);
        updateTxMeta(txId, old -> new TxStateMeta(PENDING, coordinatorId(), null));

        if (!readOnly) {
            return new ReadWriteTransactionImpl(this, timestampTracker, txId);
        }

        HybridTimestamp observableTimestamp = timestampTracker.get();

        HybridTimestamp readTimestamp = observableTimestamp != null
                ? HybridTimestamp.max(observableTimestamp, currentReadTimestamp())
                : currentReadTimestamp();

        lowWatermarkReadWriteLock.readLock().lock();

        try {
            HybridTimestamp lowWatermark1 = this.lowWatermark.get();

            readOnlyTxFutureById.compute(new TxIdAndTimestamp(readTimestamp, txId), (txIdAndTimestamp, readOnlyTxFuture) -> {
                assert readOnlyTxFuture == null : "previous transaction has not completed yet: " + txIdAndTimestamp;

                if (lowWatermark1 != null && readTimestamp.compareTo(lowWatermark1) <= 0) {
                    throw new IgniteInternalException(
                            TX_READ_ONLY_TOO_OLD_ERR,
                            "Timestamp of read-only transaction must be greater than the low watermark: [txTimestamp={}, lowWatermark={}]",
                            readTimestamp, lowWatermark1
                    );
                }

                return new CompletableFuture<>();
            });

            return new ReadOnlyTransactionImpl(this, timestampTracker, txId, readTimestamp);
        } finally {
            lowWatermarkReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Current read timestamp, for calculation of read timestamp of read-only transactions.
     *
     * @return Current read timestamp.
     */
    private HybridTimestamp currentReadTimestamp() {
        HybridTimestamp now = clock.now();

        return new HybridTimestamp(now.getPhysical()
                - idleSafeTimePropagationPeriodMsSupplier.getAsLong()
                - HybridTimestamp.CLOCK_SKEW,
                0
        );
    }

    @Override
    public TxStateMeta stateMeta(UUID txId) {
        return txStateMap.get(txId);
    }

    @Override
    public void updateTxMeta(UUID txId, Function<TxStateMeta, TxStateMeta> updater) {
        txStateMap.compute(txId, (k, oldMeta) -> {
            TxStateMeta newMeta = updater.apply(oldMeta);

            if (newMeta == null) {
                return null;
            }

            TxState oldState = oldMeta == null ? null : oldMeta.txState();

            return checkTransitionCorrectness(oldState, newMeta.txState()) ? newMeta : oldMeta;
        });
    }

    @Override
    public void finishFull(HybridTimestampTracker timestampTracker, UUID txId, boolean commit) {
        TxState finalState;

        if (commit) {
            timestampTracker.update(clock.now());

            finalState = COMMITED;
        } else {
            finalState = ABORTED;
        }

        updateTxMeta(txId, old -> new TxStateMeta(finalState, old.txCoordinatorId(), old.commitTimestamp()));
    }

    private @Nullable HybridTimestamp commitTimestamp(boolean commit) {
        return commit ? clock.now() : null;
    }

    private String coordinatorId() {
        return localNodeId.get();
    }

    @Override
    public CompletableFuture<Void> finish(
            HybridTimestampTracker observableTimestampTracker,
            TablePartitionId commitPartition,
            boolean commit,
            Map<TablePartitionId, Long> enlistedGroups,
            UUID txId
    ) {
        assert enlistedGroups != null;

        if (enlistedGroups.isEmpty()) {
            // If there are no enlisted groups, just update local state - we already marked the tx as finished.
            updateTxMeta(txId, old -> coordinatorFinalTxStateMeta(commit, commitTimestamp(commit)));

            return completedFuture(null);
        }

        // Here we put finishing state meta into the local map, so that all concurrent operations trying to read tx state
        // with using read timestamp could see that this transaction is finishing, see #transactionMetaReadTimestampAware(txId, timestamp).
        // None of them now are able to update node's clock with read timestamp and we can create the commit timestamp that is greater
        // than all the read timestamps processed before.
        // Every concurrent operation will now use a finish future from the finishing state meta and get only final transaction
        // state after the transaction is finished.
        TxStateMetaFinishing finishingStateMeta = new TxStateMetaFinishing(coordinatorId());

        updateTxMeta(txId, old -> finishingStateMeta);

        AtomicBoolean performingFinish = new AtomicBoolean();
        TxContext tuple = txCtxMap.compute(txId, (uuid, tuple0) -> {
            if (tuple0 == null) {
                tuple0 = new TxContext(); // No writes enlisted.
            }

            if (!tuple0.isTxFinishing()) {
                tuple0.finishTx();

                performingFinish.set(true);
            }

            return tuple0;
        });

        // This is a finishing thread.
        if (performingFinish.get()) {
            // Wait for commit acks first, then proceed with the finish request.
            return tuple.performFinish(commit, ignored ->
                    prepareFinish(
                            observableTimestampTracker,
                            commitPartition,
                            commit,
                            enlistedGroups,
                            txId,
                            finishingStateMeta.txFinishFuture()
                    ));
        }
        // The method `performFinish` above has a side effect on `finishInProgressFuture` future,
        // it kicks off another future that will complete it.
        return tuple.finishInProgressFuture;
    }

    private CompletableFuture<Void> prepareFinish(
            HybridTimestampTracker observableTimestampTracker,
            TablePartitionId commitPartition,
            boolean commit,
            Map<TablePartitionId, Long> enlistedGroups,
            UUID txId,
            CompletableFuture<TransactionMeta> txFinishFuture
    ) {
        HybridTimestamp commitTimestamp = commitTimestamp(commit);
        // In case of commit it's required to check whether current primaries are still the same that were enlisted and whether
        // given primaries are not expired or, in other words, whether commitTimestamp is less or equal to the enlisted primaries
        // expiration timestamps.
        CompletableFuture<Void> verificationFuture =
                commit ? verifyCommitTimestamp(enlistedGroups, commitTimestamp) : completedFuture(null);

        return verificationFuture.handle(
                        (unused, throwable) -> {
                            boolean verifiedCommit = throwable == null && commit;

                            Collection<ReplicationGroupId> replicationGroupIds = new HashSet<>(enlistedGroups.keySet());

                            return durableFinish(
                                    observableTimestampTracker,
                                    commitPartition,
                                    verifiedCommit,
                                    replicationGroupIds,
                                    txId,
                                    commitTimestamp,
                                    txFinishFuture);
                        })
                .thenCompose(Function.identity())
                // verification future is added in order to share proper exception with the client
                .thenCompose(r -> verificationFuture);
    }

    /**
     * Durable finish request.
     */
    private CompletableFuture<Void> durableFinish(
            HybridTimestampTracker observableTimestampTracker,
            TablePartitionId commitPartition,
            boolean commit,
            Collection<ReplicationGroupId> replicationGroupIds,
            UUID txId,
            HybridTimestamp commitTimestamp,
            CompletableFuture<TransactionMeta> txFinishFuture
    ) {
        return inBusyLockAsync(busyLock, () -> findPrimaryReplica(commitPartition, clock.now())
                .thenCompose(meta ->
                        makeFinishRequest(
                                observableTimestampTracker,
                                commitPartition,
                                meta.getLeaseholder(),
                                meta.getStartTime().longValue(),
                                commit,
                                replicationGroupIds,
                                txId,
                                commitTimestamp,
                                txFinishFuture
                        ))
                .handle((res, ex) -> {
                    if (ex != null) {
                        Throwable cause = ExceptionUtils.unwrapCause(ex);

                        if (cause instanceof TransactionException) {
                            TransactionException transactionException = (TransactionException) cause;

                            if (transactionException.code() == TX_WAS_ABORTED_ERR) {
                                updateTxMeta(txId, old -> {
                                    TxStateMeta txStateMeta = new TxStateMeta(ABORTED, old.txCoordinatorId(), null);

                                    txFinishFuture.complete(txStateMeta);

                                    return txStateMeta;
                                });

                                return CompletableFuture.<Void>failedFuture(cause);
                            }
                        }

                        if (TransactionFailureHandler.isRecoverable(cause)) {
                            LOG.warn("Failed to finish Tx. The operation will be retried [txId={}].", ex, txId);

                            return durableFinish(
                                    observableTimestampTracker,
                                    commitPartition,
                                    commit,
                                    replicationGroupIds,
                                    txId,
                                    commitTimestamp,
                                    txFinishFuture
                            );
                        } else {
                            LOG.warn("Failed to finish Tx [txId={}].", ex, txId);

                            return CompletableFuture.<Void>failedFuture(cause);
                        }
                    }

                    return CompletableFuture.<Void>completedFuture(null);
                })
                .thenCompose(Function.identity()));
    }

    private CompletableFuture<Void> makeFinishRequest(
            HybridTimestampTracker observableTimestampTracker,
            TablePartitionId commitPartition,
            String primaryConsistentId,
            Long term,
            boolean commit,
            Collection<ReplicationGroupId> replicationGroupIds,
            UUID txId,
            HybridTimestamp commitTimestamp,
            CompletableFuture<TransactionMeta> txFinishFuture
    ) {
        LOG.debug("Finish [partition={}, node={}, term={} commit={}, txId={}, groups={}",
                commitPartition, primaryConsistentId, term, commit, txId, replicationGroupIds);

        TxFinishReplicaRequest req = FACTORY.txFinishReplicaRequest()
                .txId(txId)
                .timestampLong(clock.nowLong())
                .groupId(commitPartition)
                .groups(replicationGroupIds)
                // In case of verification future failure transaction will be rolled back.
                .commit(commit)
                .commitTimestampLong(hybridTimestampToLong(commitTimestamp))
                .term(term)
                .build();

        return replicaService.invoke(primaryConsistentId, req)
                .thenRun(() -> {
                    updateTxMeta(txId, old -> {
                        if (isFinalState(old.txState())) {
                            txFinishFuture.complete(old);

                            return old;
                        }

                        assert old instanceof TxStateMetaFinishing;

                        TxStateMeta finalTxStateMeta = coordinatorFinalTxStateMeta(commit, commitTimestamp);

                        txFinishFuture.complete(finalTxStateMeta);

                        return finalTxStateMeta;
                    });

                    if (commit) {
                        observableTimestampTracker.update(commitTimestamp);
                    }
                });
    }

    private CompletableFuture<ReplicaMeta> findPrimaryReplica(TablePartitionId partitionId, HybridTimestamp now) {
        return placementDriver.awaitPrimaryReplica(partitionId, now, AWAIT_PRIMARY_REPLICA_TIMEOUT, SECONDS)
                .handle((primaryReplica, e) -> {
                    if (e != null) {
                        LOG.error("Failed to retrieve primary replica for partition {}", e, partitionId);

                        throw withCause(TransactionException::new, REPLICA_UNAVAILABLE_ERR,
                                "Failed to get the primary replica"
                                        + " [tablePartitionId=" + partitionId + ", awaitTimestamp=" + now + ']', e);
                    }

                    return primaryReplica;
                });
    }

    @Override
    public CompletableFuture<Void> cleanup(
            String primaryConsistentId,
            TablePartitionId tablePartitionId,
            UUID txId,
            boolean commit,
            @Nullable HybridTimestamp commitTimestamp
    ) {
        return replicaService.invoke(
                primaryConsistentId,
                FACTORY.txCleanupReplicaRequest()
                        .groupId(tablePartitionId)
                        .timestampLong(clock.nowLong())
                        .txId(txId)
                        .commit(commit)
                        .commitTimestampLong(hybridTimestampToLong(commitTimestamp))
                        .build()
        );
    }

    @Override
    public int finished() {
        return (int) txStateMap.entrySet().stream()
                .filter(e -> isFinalState(e.getValue().txState()))
                .count();
    }

    @Override
    public int pending() {
        return (int) txStateMap.entrySet().stream()
                .filter(e -> e.getValue().txState() == PENDING)
                .count();
    }

    @Override
    public void start() {
        replicaService.messagingService().addMessageHandler(ReplicaMessageGroup.class, this);
    }

    @Override
    public void stop() {
        if (!stopGuard.compareAndSet(false, true)) {
            return;
        }

        busyLock.block();

        shutdownAndAwaitTermination(cleanupExecutor, 10, TimeUnit.SECONDS);
    }

    @Override
    public LockManager lockManager() {
        return lockManager;
    }

    @Override
    public CompletableFuture<Void> executeCleanupAsync(Runnable runnable) {
        return runAsync(runnable, cleanupExecutor);
    }

    @Override
    public CompletableFuture<?> executeCleanupAsync(Supplier<CompletableFuture<?>> action) {
        return supplyAsync(action, cleanupExecutor).thenCompose(f -> f);
    }

    CompletableFuture<Void> completeReadOnlyTransactionFuture(TxIdAndTimestamp txIdAndTimestamp) {
        CompletableFuture<Void> readOnlyTxFuture = readOnlyTxFutureById.remove(txIdAndTimestamp);

        assert readOnlyTxFuture != null : txIdAndTimestamp;

        readOnlyTxFuture.complete(null);

        return readOnlyTxFuture;
    }

    @Override
    public CompletableFuture<Void> updateLowWatermark(HybridTimestamp newLowWatermark) {
        lowWatermarkReadWriteLock.writeLock().lock();

        try {
            lowWatermark.updateAndGet(previousLowWatermark -> {
                if (previousLowWatermark == null) {
                    return newLowWatermark;
                }

                assert newLowWatermark.compareTo(previousLowWatermark) > 0 :
                        "lower watermark should be growing: [previous=" + previousLowWatermark + ", new=" + newLowWatermark + ']';

                return newLowWatermark;
            });

            TxIdAndTimestamp upperBound = new TxIdAndTimestamp(newLowWatermark, new UUID(Long.MAX_VALUE, Long.MAX_VALUE));

            List<CompletableFuture<Void>> readOnlyTxFutures = List.copyOf(readOnlyTxFutureById.headMap(upperBound, true).values());

            return allOf(readOnlyTxFutures.toArray(CompletableFuture[]::new));
        } finally {
            lowWatermarkReadWriteLock.writeLock().unlock();
        }
    }

    @Override
    public boolean addInflight(UUID txId) {
        boolean[] res = {true};

        txCtxMap.compute(txId, (uuid, tuple) -> {
            if (tuple == null) {
                tuple = new TxContext();
            }

            if (tuple.isTxFinishing()) {
                res[0] = false;
                return tuple;
            } else {
                //noinspection NonAtomicOperationOnVolatileField
                tuple.inflights++;
            }

            return tuple;
        });

        return res[0];
    }

    @Override
    public void removeInflight(UUID txId) {
        TxContext tuple = txCtxMap.compute(txId, (uuid, ctx) -> {
            assert ctx != null && ctx.inflights > 0 : ctx;

            //noinspection NonAtomicOperationOnVolatileField
            ctx.inflights--;

            return ctx;
        });

        // Avoid completion under lock.
        tuple.onRemovedInflights();
    }

    @Override
    public void onReceived(NetworkMessage message, String senderConsistentId, @Nullable Long correlationId) {
        if (!(message instanceof ReplicaResponse) || correlationId != null) {
            return;
        }

        // Ignore error responses here. A transaction will be rolled back in other place.
        if (message instanceof ErrorReplicaResponse) {
            return;
        }

        // Process directly sent response.
        ReplicaResponse request = (ReplicaResponse) message;

        Object result = request.result();

        if (result instanceof UUID) {
            removeInflight((UUID) result);
        }
    }

    /**
     * Creates final {@link TxStateMeta} for coordinator node.
     *
     * @param commit Commit flag.
     * @param commitTimestamp Commit timestamp.
     * @return Transaction meta.
     */
    private TxStateMeta coordinatorFinalTxStateMeta(boolean commit, @Nullable HybridTimestamp commitTimestamp) {
        return new TxStateMeta(commit ? COMMITED : ABORTED, coordinatorId(), commitTimestamp);
    }

    /**
     * Check whether previously enlisted primary replicas aren't expired and that commit timestamp is less or equal than primary replicas
     * expiration timestamp. Given method will either complete result future with void or {@link PrimaryReplicaExpiredException}
     *
     * @param enlistedGroups enlisted primary replicas map from groupId to enlistment consistency token.
     * @param commitTimestamp Commit timestamp.
     * @return Verification future.
     */
    private CompletableFuture<Void> verifyCommitTimestamp(Map<TablePartitionId, Long> enlistedGroups, HybridTimestamp commitTimestamp) {
        var verificationFutures = new CompletableFuture[enlistedGroups.size()];
        int cnt = -1;

        for (Map.Entry<TablePartitionId, Long> enlistedGroup : enlistedGroups.entrySet()) {
            TablePartitionId groupId = enlistedGroup.getKey();
            Long expectedEnlistmentConsistencyToken = enlistedGroup.getValue();

            verificationFutures[++cnt] = placementDriver.getPrimaryReplica(groupId, commitTimestamp)
                    .thenAccept(currentPrimaryReplica -> {
                        if (currentPrimaryReplica == null
                                || !expectedEnlistmentConsistencyToken.equals(currentPrimaryReplica.getStartTime().longValue())
                        ) {
                            throw new PrimaryReplicaExpiredException(
                                    groupId,
                                    expectedEnlistmentConsistencyToken,
                                    commitTimestamp,
                                    currentPrimaryReplica
                            );
                        } else {
                            assert commitTimestamp.compareTo(currentPrimaryReplica.getExpirationTime()) <= 0 :
                                    IgniteStringFormatter.format(
                                            "Commit timestamp is greater than primary replica expiration timestamp:"
                                                    + " [groupId = {}, commit timestamp = {}, primary replica expiration timestamp = {}]",
                                            groupId, commitTimestamp, currentPrimaryReplica.getExpirationTime());

                        }
                    });
        }

        return allOf(verificationFutures);
    }

    private static class TxContext {
        volatile long inflights = 0; // Updated under lock.
        private final CompletableFuture<Void> waitRepFut = new CompletableFuture<>();
        volatile CompletableFuture<Void> finishInProgressFuture = null;

        CompletableFuture<Void> performFinish(boolean commit, Function<Void, CompletableFuture<Void>> finishAction) {
            waitReadyToFinish(commit)
                    .thenCompose(finishAction)
                    .handle((ignored, err) -> {
                        if (err == null) {
                            finishInProgressFuture.complete(null);
                        } else {
                            finishInProgressFuture.completeExceptionally(err);
                        }
                        return null;
                    });

            return finishInProgressFuture;
        }

        private CompletableFuture<Void> waitReadyToFinish(boolean commit) {
            return commit ? waitNoInflights() : completedFuture(null);
        }

        private CompletableFuture<Void> waitNoInflights() {
            if (inflights == 0) {
                waitRepFut.complete(null);
            }
            return waitRepFut;
        }

        void onRemovedInflights() {
            if (inflights == 0 && finishInProgressFuture != null) {
                waitRepFut.complete(null);
            }
        }

        void finishTx() {
            finishInProgressFuture = new CompletableFuture<>();
        }

        boolean isTxFinishing() {
            return finishInProgressFuture != null;
        }

        @Override
        public String toString() {
            return "TxContext [inflights=" + inflights + ", waitRepFut=" + waitRepFut + ", finishFut=" + finishInProgressFuture + ']';
        }
    }

    private static class TransactionFailureHandler {
        private static final Set<Class<? extends Throwable>> RECOVERABLE = Set.of(
                TimeoutException.class,
                IOException.class,
                ReplicationTimeoutException.class,
                PrimaryReplicaMissException.class
        );

        /**
         * Check if the provided exception is recoverable.
         * A recoverable transaction is the one that we can send a 'retry' request for.
         *
         * @param throwable Exception to test.
         * @return {@code true} if recoverable, {@code false} otherwise.
         */
        static boolean isRecoverable(Throwable throwable) {
            if (throwable == null) {
                return false;
            }

            Throwable candidate = ExceptionUtils.unwrapCause(throwable);

            for (Class<? extends Throwable> recoverableClass : RECOVERABLE) {
                if (recoverableClass.isAssignableFrom(candidate.getClass())) {
                    return true;
                }
            }

            return false;
        }
    }
}

