/*
 * This file is part of ToroDB.
 *
 * ToroDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ToroDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with repl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2016 8Kdata.
 *
 */
package com.torodb.mongodb.repl.oplogreplier.batch;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.eightkdata.mongowp.server.api.oplog.DbCmdOplogOperation;
import com.eightkdata.mongowp.server.api.oplog.OplogOperation;
import com.eightkdata.mongowp.server.api.tools.Empty;
import com.torodb.core.exceptions.user.UniqueIndexViolationException;
import com.torodb.core.exceptions.user.UserException;
import com.torodb.core.metrics.MetricNameFactory;
import com.torodb.core.metrics.ToroMetricRegistry;
import com.torodb.core.retrier.Retrier;
import com.torodb.core.retrier.Retrier.Hint;
import com.torodb.core.retrier.RetrierAbortException;
import com.torodb.core.retrier.RetrierGiveUpException;
import com.torodb.core.transaction.RollbackException;
import com.torodb.mongodb.core.ExclusiveWriteMongodTransaction;
import com.torodb.mongodb.core.MongodConnection;
import com.torodb.mongodb.core.MongodServer;
import com.torodb.mongodb.core.WriteMongodTransaction;
import com.torodb.mongodb.repl.oplogreplier.ApplierContext;
import com.torodb.mongodb.repl.oplogreplier.OplogOperationApplier;
import com.torodb.mongodb.repl.oplogreplier.OplogOperationApplier.OplogApplyingException;

/**
 *
 */
@ThreadSafe
public class AnalyzedOplogBatchExecutor implements
        AnalyzedOplogBatchVisitor<OplogOperation, ApplierContext, RetrierGiveUpException> {

    private final AnalyzedOplogBatchExecutorMetrics metrics;
    private final OplogOperationApplier oplogOperationApplier;
    private final MongodServer server;
    private final Retrier retrier;
    private final NamespaceJobExecutor namespaceJobExecutor;

    @Inject
    public AnalyzedOplogBatchExecutor(AnalyzedOplogBatchExecutorMetrics metrics,
            OplogOperationApplier oplogOperationApplier, MongodServer server,
            Retrier retrier, NamespaceJobExecutor namespaceJobExecutor) {
        this.metrics = metrics;
        this.oplogOperationApplier = oplogOperationApplier;
        this.server = server;
        this.retrier = retrier;
        this.namespaceJobExecutor = namespaceJobExecutor;
    }

    public void execute(OplogOperation op, ApplierContext context)
            throws OplogApplyingException, RollbackException, UserException {
        try (MongodConnection connection = server.openConnection();
                ExclusiveWriteMongodTransaction mongoTransaction = connection.openExclusiveWriteTransaction()) {

            oplogOperationApplier.apply(op, mongoTransaction, context);
            mongoTransaction.commit();
        }
    }

    public void execute(CudAnalyzedOplogBatch cudBatch, ApplierContext context)
            throws RollbackException, UserException, NamespaceJobExecutionException {
        try (MongodConnection connection = server.openConnection()) {

            Iterator<NamespaceJob> it = cudBatch.streamNamespaceJobs().iterator();
            while (it.hasNext()) {
                execute(it.next(), context, connection);
            }
        }
    }

    protected void execute(NamespaceJob job, ApplierContext applierContext, 
            MongodConnection connection)  throws RollbackException, UserException,
            NamespaceJobExecutionException {
        try (Context timerContext = metrics.getNamespaceBatchTimer().time()) {
            boolean optimisticDeleteAndCreate = applierContext.isReapplying().orElse(true);
            try {
                execute(job, applierContext, connection, optimisticDeleteAndCreate);
            } catch (UniqueIndexViolationException ex) {
                assert optimisticDeleteAndCreate : "Unique index violations should not happen when "
                        + "pesimistic delete and create is executed";
                execute(job, applierContext, connection, false);
            }
        }
    }

    private void execute(NamespaceJob job, ApplierContext applierContext,
            MongodConnection connection, boolean optimisticDeleteAndCreate)
            throws RollbackException, UserException, NamespaceJobExecutionException, UniqueIndexViolationException {
        try (WriteMongodTransaction mongoTransaction = connection.openWriteTransaction()) {
            namespaceJobExecutor.apply(job, mongoTransaction, applierContext, optimisticDeleteAndCreate);
            mongoTransaction.commit();
        }
    }

    @Override
    public OplogOperation visit(SingleOpAnalyzedOplogBatch batch, ApplierContext arg) throws
            RetrierGiveUpException {
        OplogOperation operation = batch.getOperation();

        try (Context context = metrics.getSingleOpTimer(operation).time()) {
            try {
                execute(batch.getOperation(), arg);
            } catch (OplogApplyingException | UserException ex) {
                throw new RetrierGiveUpException("Unexpected exception while replying", ex);
            } catch (RollbackException ex) {
                ApplierContext retryingReplingContext = new ApplierContext.Builder()
                        .setReapplying(true)
                        .setUpdatesAsUpserts(true)
                        .build();
                retrier.retry(() -> {
                    try {
                        execute(batch.getOperation(), retryingReplingContext);
                        return Empty.getInstance();
                    } catch (OplogApplyingException ex2) {
                        throw new RetrierAbortException("Unexpected exception while replying", ex2);
                    }
                }, Hint.CRITICAL, Hint.TIME_SENSIBLE);
            }
        }

        return batch.getOperation();
    }

    @Override
    public OplogOperation visit(CudAnalyzedOplogBatch batch, ApplierContext arg) throws
            RetrierGiveUpException {
        metrics.getCudBatchSize().update(batch.getOriginalBatch().size());
        try (Context context = metrics.getCudBatchTimer().time()) {
            try {
                execute(batch, arg);
            } catch (UserException | NamespaceJobExecutionException ex) {
                throw new RetrierGiveUpException("Unexpected exception while replying", ex);
            } catch (RollbackException ex) {
                ApplierContext retryingReplingContext = new ApplierContext.Builder()
                        .setReapplying(true)
                        .setUpdatesAsUpserts(true)
                        .build();
                retrier.retry(() -> {
                    try {
                        execute(batch, retryingReplingContext);
                        return Empty.getInstance();
                    } catch (UserException | NamespaceJobExecutionException ex2) {
                        throw new RetrierAbortException("Unexpected user exception while applying "
                                + "the batch " + batch, ex2);
                    }
                }, Hint.CRITICAL, Hint.TIME_SENSIBLE);
            }
        }

        List<OplogOperation> originalBatch = batch.getOriginalBatch();
        return originalBatch.get(originalBatch.size() - 1);
    }

    public OplogOperation apply(AnalyzedOplogBatch batch, ApplierContext replContext) throws
            RetrierGiveUpException, RetrierAbortException {
        return batch.accept(this, replContext);
    }

    protected MongodServer getServer() {
        return server;
    }

    public static class AnalyzedOplogBatchExecutorMetrics {

        protected static final MetricNameFactory NAME_FACTORY
                = new MetricNameFactory("OplogBatchExecutor");
        private final ConcurrentMap<String, Timer> singleOpTimers = new ConcurrentHashMap<>();
        private final ToroMetricRegistry metricRegistry;
        private final Histogram cudBatchSize;
        private final Timer cudBatchTimer;
        private final Timer namespaceBatchTimer;

        @Inject
        public AnalyzedOplogBatchExecutorMetrics(ToroMetricRegistry metricRegistry) {
            this.metricRegistry = metricRegistry;
            this.cudBatchSize = metricRegistry.histogram(NAME_FACTORY.createMetricName("batchSize"));
            this.cudBatchTimer = metricRegistry.timer(NAME_FACTORY.createMetricName("cudTimer"));
            this.namespaceBatchTimer = metricRegistry.timer(NAME_FACTORY.createMetricName("namespaceTimer"));
        }

        /**
         * Returns the timer associated with {@link SingleOpAnalyzedOplogBatch} that contains the
         * given operation.
         *
         * @param singleOplogOp
         * @return
         */
        public Timer getSingleOpTimer(OplogOperation singleOplogOp) {
            String mapKey = getMapKey(singleOplogOp);
            return singleOpTimers.computeIfAbsent(
                    mapKey,
                    (key) -> createSingleTimer(singleOplogOp, key)
            );
        }

        public Histogram getCudBatchSize() {
            return cudBatchSize;
        }

        public Timer getCudBatchTimer() {
            return cudBatchTimer;
        }

        public Timer getNamespaceBatchTimer() {
            return namespaceBatchTimer;
        }

        @Nonnull
        private String getMapKey(OplogOperation oplogOp) {
            if (oplogOp instanceof DbCmdOplogOperation) {
                DbCmdOplogOperation cmdOp = (DbCmdOplogOperation) oplogOp;
                return cmdOp.getCommandName().orElse("unknownCmd");
            }
            return oplogOp.getType().name();
        }

        private Timer createSingleTimer(OplogOperation oplogOp, String mapKey) {
            String prefix = "single-";
            if (oplogOp instanceof DbCmdOplogOperation) {
                return metricRegistry.timer(NAME_FACTORY.createMetricName(prefix + mapKey));
            }
            return metricRegistry.timer(prefix + mapKey.toLowerCase(Locale.ENGLISH));
        }
    }

}
