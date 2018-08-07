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

import com.google.common.base.Supplier;
import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.atlasdb.cache.TimestampCache;
import com.palantir.atlasdb.cleaner.api.Cleaner;
import com.palantir.atlasdb.keyvalue.api.ClusterAvailabilityStatus;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.transaction.api.AtlasDbConstraintCheckingMode;
import com.palantir.atlasdb.transaction.api.ConditionAwareTransactionTask;
import com.palantir.atlasdb.transaction.api.KeyValueServiceStatus;
import com.palantir.atlasdb.transaction.api.PreCommitCondition;
import com.palantir.atlasdb.transaction.api.Transaction;
import com.palantir.atlasdb.transaction.api.TransactionFailedRetriableException;
import com.palantir.atlasdb.transaction.api.TransactionReadSentinelBehavior;
import com.palantir.atlasdb.transaction.api.TransactionTask;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.util.MetricsManager;

public final class ReadOnlyTransactionManager extends AbstractLockAwareTransactionManager  {
    private final MetricsManager metricsManager;
    private final KeyValueService keyValueService;
    private final TransactionService transactionService;
    private final AtlasDbConstraintCheckingMode constraintCheckingMode;
    private final Supplier<Long> startTimestamp;
    private final TransactionReadSentinelBehavior readSentinelBehavior;
    private final boolean allowHiddenTableAccess;
    private final int defaultGetRangesConcurrency;

    public ReadOnlyTransactionManager(
            MetricsManager metricsManager,
            KeyValueService keyValueService,
            TransactionService transactionService,
            AtlasDbConstraintCheckingMode constraintCheckingMode,
            Supplier<Long> startTimestamp,
            TransactionReadSentinelBehavior readSentinelBehavior,
            boolean allowHiddenTableAccess,
            int defaultGetRangesConcurrency,
            TimestampCache timestampCache) {
        super(metricsManager, timestampCache);
        this.metricsManager = metricsManager;
        this.keyValueService = keyValueService;
        this.transactionService = transactionService;
        this.constraintCheckingMode = constraintCheckingMode;
        this.startTimestamp = startTimestamp;
        this.readSentinelBehavior = readSentinelBehavior;
        this.allowHiddenTableAccess = allowHiddenTableAccess;
        this.defaultGetRangesConcurrency = defaultGetRangesConcurrency;
    }

    @Override
    public <T, E extends Exception> T runTaskReadOnly(TransactionTask<T, E> task) throws E {
        return runTaskWithConditionReadOnly(NO_OP_CONDITION, (txn, condition) -> task.execute(txn));
    }

    @Override
    public void close() {
        super.close();
        keyValueService.close();
    }

    @Override
    public <T, E extends Exception> T runTaskThrowOnConflict(TransactionTask<T, E> task) throws E,
            TransactionFailedRetriableException {
        throw new UnsupportedOperationException("this manager is read only");
    }

    @Override
    public long getImmutableTimestamp() {
        return Long.MAX_VALUE;
    }

    @Override
    public KeyValueServiceStatus getKeyValueServiceStatus() {
        ClusterAvailabilityStatus clusterAvailabilityStatus = keyValueService.getClusterAvailabilityStatus();
        switch (clusterAvailabilityStatus) {
            case ALL_AVAILABLE:
            case QUORUM_AVAILABLE:
                return KeyValueServiceStatus.HEALTHY_ALL_OPERATIONS;
            case NO_QUORUM_AVAILABLE:
                return KeyValueServiceStatus.UNHEALTHY;
            case TERMINAL:
                return KeyValueServiceStatus.TERMINAL;
            default:
                log.warn("The kvs returned a non-standard availability status: {}", clusterAvailabilityStatus);
                return KeyValueServiceStatus.UNHEALTHY;
        }
    }

    @Override
    public long getUnreadableTimestamp() {
        return Long.MAX_VALUE;
    }

    @Override
    public void clearTimestampCache() {}

    @Override
    public void registerClosingCallback(Runnable closingCallback) {
        throw new UnsupportedOperationException("Not supported on this transaction manager");
    }

    @Override
    public Transaction setupRunTaskWithConditionThrowOnConflict(PreCommitCondition condition) {
        throw new UnsupportedOperationException("Not supported on this transaction manager");
    }

    @Override
    public <T, E extends Exception> T finishRunTaskWithLockThrowOnConflict(Transaction tx,
            TransactionTask<T, E> task) throws TransactionFailedRetriableException {
        throw new UnsupportedOperationException("Not supported on this transaction manager");
    }

    @Override
    public Cleaner getCleaner() {
        return null;
    }

    @Override
    public KeyValueService getKeyValueService() {
        return null;
    }

    @Override
    public <T, C extends PreCommitCondition, E extends Exception> T runTaskWithConditionThrowOnConflict(C condition,
            ConditionAwareTransactionTask<T, C, E> task) throws E, TransactionFailedRetriableException {
        throw new UnsupportedOperationException("this manager is read only");
    }

    @Override
    public <T, C extends PreCommitCondition, E extends Exception> T runTaskWithConditionReadOnly(C condition,
            ConditionAwareTransactionTask<T, C, E> task) throws E {
        checkOpen();
        SnapshotTransaction txn = new ShouldNotDeleteAndRollbackTransaction(
                metricsManager,
                keyValueService,
                transactionService,
                startTimestamp.get(),
                constraintCheckingMode,
                readSentinelBehavior,
                allowHiddenTableAccess,
                timestampValidationReadCache,
                MoreExecutors.newDirectExecutorService(),
                defaultGetRangesConcurrency);
        return runTaskThrowOnConflict((transaction) -> task.execute(transaction, condition),
                new ReadTransaction(txn, txn.sweepStrategyManager));
    }
}
