/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.transaction.impl;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.palantir.atlasdb.cache.TimestampCache;
import com.palantir.atlasdb.cleaner.NoOpCleaner;
import com.palantir.atlasdb.cleaner.api.Cleaner;
import com.palantir.atlasdb.keyvalue.api.ClusterAvailabilityStatus;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.protos.generated.TransactionService.TimestampRange;
import com.palantir.atlasdb.sweep.queue.MultiTableSweepQueueWriter;
import com.palantir.atlasdb.timelock.hackweek.JamesTransactionService;
import com.palantir.atlasdb.transaction.api.AtlasDbConstraintCheckingMode;
import com.palantir.atlasdb.transaction.api.ConditionAwareTransactionTask;
import com.palantir.atlasdb.transaction.api.KeyValueServiceStatus;
import com.palantir.atlasdb.transaction.api.PreCommitCondition;
import com.palantir.atlasdb.transaction.api.Transaction;
import com.palantir.atlasdb.transaction.api.Transaction.TransactionType;
import com.palantir.atlasdb.transaction.api.TransactionFailedRetriableException;
import com.palantir.atlasdb.transaction.api.TransactionReadSentinelBehavior;
import com.palantir.atlasdb.transaction.api.TransactionTask;
import com.palantir.atlasdb.transaction.impl.logging.CommitProfileProcessor;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.common.base.Throwables;

/* package */ class SnapshotTransactionManager extends AbstractLockAwareTransactionManager {
    private static final int NUM_RETRIES = 10;

    final MetricsManager metricsManager;
    final KeyValueService keyValueService;
    final TransactionService transactionService;
    final JamesTransactionService james;
    final ConflictDetectionManager conflictDetectionManager;
    final SweepStrategyManager sweepStrategyManager;
    final Supplier<AtlasDbConstraintCheckingMode> constraintModeSupplier;
    final AtomicLong recentImmutableTs = new AtomicLong(-1L);
    final Cleaner cleaner;
    final boolean allowHiddenTableAccess;
    protected final Supplier<Long> lockAcquireTimeoutMs;
    final ExecutorService getRangesExecutor;
    final ExecutorService deleteExecutor;
    final int defaultGetRangesConcurrency;
    final MultiTableSweepQueueWriter sweepQueueWriter;

    final List<Runnable> closingCallbacks;
    final AtomicBoolean isClosed;

    final CommitProfileProcessor commitProfileProcessor;

    protected SnapshotTransactionManager(
            MetricsManager metricsManager,
            KeyValueService keyValueService,
            JamesTransactionService james,
            TransactionService transactionService,
            Supplier<AtlasDbConstraintCheckingMode> constraintModeSupplier,
            ConflictDetectionManager conflictDetectionManager,
            SweepStrategyManager sweepStrategyManager,
            Cleaner cleaner,
            boolean allowHiddenTableAccess,
            Supplier<Long> lockAcquireTimeoutMs,
            int concurrentGetRangesThreadPoolSize,
            int defaultGetRangesConcurrency,
            TimestampCache timestampCache,
            MultiTableSweepQueueWriter sweepQueueWriter,
            ExecutorService deleteExecutor) {
        super(metricsManager, timestampCache);
        this.metricsManager = metricsManager;
        this.keyValueService = keyValueService;
        this.james = james;
        this.transactionService = transactionService;
        this.conflictDetectionManager = conflictDetectionManager;
        this.sweepStrategyManager = sweepStrategyManager;
        this.constraintModeSupplier = constraintModeSupplier;
        this.cleaner = cleaner;
        this.allowHiddenTableAccess = allowHiddenTableAccess;
        this.lockAcquireTimeoutMs = lockAcquireTimeoutMs;
        this.closingCallbacks = new CopyOnWriteArrayList<>();
        this.isClosed = new AtomicBoolean(false);
        this.getRangesExecutor = createGetRangesExecutor(concurrentGetRangesThreadPoolSize);
        this.defaultGetRangesConcurrency = defaultGetRangesConcurrency;
        this.sweepQueueWriter = sweepQueueWriter;
        this.deleteExecutor = deleteExecutor;
        this.commitProfileProcessor = CommitProfileProcessor.createDefault(metricsManager);
    }

    @Override
    protected boolean shouldStopRetrying(int numTimesFailed) {
        return numTimesFailed > NUM_RETRIES;
    }

    @Override
    public <T, C extends PreCommitCondition, E extends Exception> T runTaskWithConditionThrowOnConflict(
            C condition, ConditionAwareTransactionTask<T, C, E> task)
            throws E, TransactionFailedRetriableException {
        checkOpen();
        try {
            Transaction txAndLock =
                    runTimed(() -> setupRunTaskWithConditionThrowOnConflict(condition), "setupTask");
            return finishRunTaskWithLockThrowOnConflict(txAndLock,
                    transaction -> task.execute(transaction, condition));
        } finally {
            condition.cleanup();
        }
    }

    @Override
    public Transaction setupRunTaskWithConditionThrowOnConflict(PreCommitCondition condition) {
        TimestampRange range = james.startTransactions(1);
        try {
            long immutableTs = range.getImmutable();
            recordImmutableTimestamp(immutableTs);

            cleaner.punch(range.getLower());

            SnapshotTransaction transaction = createTransaction(immutableTs, range.getLower(), condition);
            return transaction;
        } catch (Throwable e) {
            // timelockService.tryUnlock(ImmutableSet.of(transactionResponse.immutableTimestamp().getLock()));
            throw Throwables.rewrapAndThrowUncheckedException(e);
        }
    }

    @Override
    public <T, E extends Exception> T finishRunTaskWithLockThrowOnConflict(Transaction txAndLock,
                                                                           TransactionTask<T, E> task)
            throws E, TransactionFailedRetriableException {
        Timer postTaskTimer = getTimer("finishTask");
        Timer.Context postTaskContext;

        SnapshotTransaction tx = (SnapshotTransaction) txAndLock;
        T result;
        try {
            result = runTaskThrowOnConflict(task, tx);
        } finally {
            postTaskContext = postTaskTimer.time();
        }
        scrubForAggressiveHardDelete(tx);
        postTaskContext.stop();
        return result;
    }

    private void scrubForAggressiveHardDelete(SnapshotTransaction tx) {
        if ((tx.getTransactionType() == TransactionType.AGGRESSIVE_HARD_DELETE) && !tx.isAborted()) {
            // t.getCellsToScrubImmediately() checks that t has been committed
            cleaner.scrubImmediately(this,
                    tx.getCellsToScrubImmediately(),
                    tx.getTimestamp(),
                    tx.getCommitTimestamp());
        }
    }

    protected SnapshotTransaction createTransaction(
            long immutableTimestamp,
            long startTimestampSupplier,
            PreCommitCondition condition) {
        return new SnapshotTransaction(
                metricsManager,
                keyValueService,
                james,
                transactionService,
                cleaner,
                startTimestampSupplier,
                conflictDetectionManager,
                sweepStrategyManager,
                immutableTimestamp,
                condition,
                constraintModeSupplier.get(),
                cleaner.getTransactionReadTimeoutMillis(),
                TransactionReadSentinelBehavior.THROW_EXCEPTION,
                allowHiddenTableAccess,
                timestampValidationReadCache,
                lockAcquireTimeoutMs.get(),
                getRangesExecutor,
                defaultGetRangesConcurrency,
                sweepQueueWriter,
                deleteExecutor,
                commitProfileProcessor);
    }

    @Override
    public <T, C extends PreCommitCondition, E extends Exception> T runTaskWithConditionReadOnly(
            C condition, ConditionAwareTransactionTask<T, C, E> task) throws E {
        checkOpen();
        TimestampRange range = james.startTransactions(1);
        long immutableTs = range.getImmutable();
        SnapshotTransaction transaction = new SnapshotTransaction(
                metricsManager,
                keyValueService,
                james,
                transactionService,
                NoOpCleaner.INSTANCE,
                range.getLower(),
                conflictDetectionManager,
                sweepStrategyManager,
                immutableTs,
                condition,
                constraintModeSupplier.get(),
                cleaner.getTransactionReadTimeoutMillis(),
                TransactionReadSentinelBehavior.THROW_EXCEPTION,
                allowHiddenTableAccess,
                timestampValidationReadCache,
                lockAcquireTimeoutMs.get(),
                getRangesExecutor,
                defaultGetRangesConcurrency,
                sweepQueueWriter,
                deleteExecutor,
                commitProfileProcessor);
        try {
            return runTaskThrowOnConflict(txn -> task.execute(txn, condition),
                    new ReadTransaction(transaction, sweepStrategyManager));
        } finally {
            james.checkReadConflicts(range.getLower(), Collections.emptyList(), Collections.emptyList());
            james.unlock(Collections.singletonList(range.getLower()));
            condition.cleanup();
        }
    }

    @Override
    public void registerClosingCallback(Runnable closingCallback) {
        Preconditions.checkNotNull(closingCallback, "Cannot register a null callback.");
        closingCallbacks.add(closingCallback);
    }

    /**
     * Frees resources used by this SnapshotTransactionManager, and invokes any callbacks registered to run on close.
     * This includes the cleaner, the key value service (and attendant thread pools), and possibly the lock service.
     *
     * Concurrency: If this method races with registerClosingCallback(closingCallback), then closingCallback
     * may be called (but is not necessarily called). Callbacks registered before the invocation of close() are
     * guaranteed to be executed (because we use a synchronized list) as long as no exceptions arise. If an exception
     * arises, then no guarantees are made with regard to subsequent callbacks being executed.
     */
    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            super.close();
            cleaner.close();
            keyValueService.close();
            shutdownExecutor(deleteExecutor);
            shutdownExecutor(getRangesExecutor);
            for (Runnable callback : Lists.reverse(closingCallbacks)) {
                callback.run();
            }
            metricsManager.deregisterMetrics();
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            // Continue with further clean-up
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void clearTimestampCache() {
        timestampValidationReadCache.clear();
    }

    /**
     * This will always return a valid ImmutableTimestamp, but it may be slightly out of date.
     * <p>
     * This method is used to optimize the perf of read only transactions because getting a new immutableTs requires
     * 2 extra remote calls which we can skip.
     */
    private long getApproximateImmutableTimestamp() {
        long recentTs = recentImmutableTs.get();
        if (recentTs >= 0) {
            return recentTs;
        }
        return getImmutableTimestamp();
    }

    @Override
    public long getImmutableTimestamp() {
        long immutableTs = james.getImmutableTimestamp().getTimestamp();
        recordImmutableTimestamp(immutableTs);
        return immutableTs;
    }

    private void recordImmutableTimestamp(long immutableTs) {
        recentImmutableTs.updateAndGet(current -> Math.max(current, immutableTs));
    }

    @Override
    public long getUnreadableTimestamp() {
        return cleaner.getUnreadableTimestamp();
    }

    @Override
    public Cleaner getCleaner() {
        return cleaner;
    }

    @Override
    public KeyValueService getKeyValueService() {
        return keyValueService;
    }

    @Override
    public KeyValueServiceStatus getKeyValueServiceStatus() {
        ClusterAvailabilityStatus clusterAvailabilityStatus = keyValueService.getClusterAvailabilityStatus();
        switch (clusterAvailabilityStatus) {
            case TERMINAL:
                return KeyValueServiceStatus.TERMINAL;
            case ALL_AVAILABLE:
                return KeyValueServiceStatus.HEALTHY_ALL_OPERATIONS;
            case QUORUM_AVAILABLE:
                return KeyValueServiceStatus.HEALTHY_BUT_NO_SCHEMA_MUTATIONS_OR_DELETES;
            case NO_QUORUM_AVAILABLE:
            default:
                return KeyValueServiceStatus.UNHEALTHY;
        }
    }

    private <T> T runTimed(Callable<T> operation, String timerName) {
        Timer.Context timer = getTimer(timerName).time();
        try {
            T response = operation.call();
            timer.stop(); // By design, we only want to consider time for operations that were successful.
            return response;
        } catch (Exception e) {
            Throwables.throwIfInstance(e, RuntimeException.class);
            throw new RuntimeException(e);
        }
    }

    private Timer getTimer(String name) {
        return metricsManager.registerOrGetTimer(SnapshotTransactionManager.class, name);
    }
}
