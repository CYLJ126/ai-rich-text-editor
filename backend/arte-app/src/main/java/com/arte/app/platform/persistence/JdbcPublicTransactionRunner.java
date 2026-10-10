package com.arte.app.platform.persistence;

import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Jdbc 事务实现类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
public final class JdbcPublicTransactionRunner implements PublicTransactionRunner {

    private final DataSource source;
    private final TransactionTemplate template;

    private record StateKey(DataSource source) {
    }

    private final Object stateKey;

    public JdbcPublicTransactionRunner(
            DataSource source, PlatformTransactionManager manager, int timeoutSeconds) {
        if (!(manager instanceof DataSourceTransactionManager jdbc)
                || jdbc.getDataSource() != source)
            throw new IllegalArgumentException(
                    "Platform transaction manager must own appDataSource");
        this.source = source;
        this.stateKey = new StateKey(source);
        template = new TransactionTemplate(manager);
        template.setTimeout(timeoutSeconds);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive() && !isTransactionActive())
            throw new IllegalStateException("Cannot join a transaction on another datasource");
        return template.execute(
                status -> {
                    try {
                        return work.get();
                    } catch (RuntimeException | Error failure) {
                        status.setRollbackOnly();
                        throw failure;
                    }
                });
    }

    @Override
    public boolean isTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(source);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T resource(Object key, Supplier<T> factory) {
        requireActive();
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Transaction synchronization is required");
        Map<Object, Object> state =
                (Map<Object, Object>) TransactionSynchronizationManager.getResource(stateKey);
        if (state == null) {
            state = new HashMap<>();
            Map<Object, Object> bound = state;
            TransactionSynchronizationManager.bindResource(stateKey, bound);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager.unbindResource(stateKey);
                        }

                        @Override
                        public void resume() {
                            TransactionSynchronizationManager.bindResource(stateKey, bound);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(stateKey);
                            bound.clear();
                        }
                    });
        }
        return (T) state.computeIfAbsent(key, ignored -> Objects.requireNonNull(factory.get()));
    }

    @Override
    public void markRollbackOnly() {
        requireActive();
        ConnectionHolder resource = (ConnectionHolder) Objects.requireNonNull(TransactionSynchronizationManager.getResource(source));
        resource.setRollbackOnly();
    }
}
