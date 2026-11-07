package com.arte.ainew.infrastructure.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ControlPreparation;
import com.arte.ainew.context.ReadOperationSnapshot;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Objects;
import java.util.function.Function;

/**
 * 数据库控制快照的读取与复用协调器
 * <p>
 * 负责让模型资格、预算、执行策略等适配器，按统一规则取得当前操作适用的控制数据。
 * 共享同次操作读取；真正写入只允许在原可写事务中重新读取来源。
 * 不负责开启或提交事务，也不是跨请求缓存。事务方法只在当前回调内共享读取，结束后会清理；授权事实仍由来源和相应门控负责核验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public final class ControlConfigurationReader {

    private static final Object KEY = new Object();
    private static final ThreadLocal<CurrentRead> CURRENT = new ThreadLocal<>();

    private record Read(ExecutionContext context, ControlConfiguration configuration, Object sourceKey) {
    }

    private record CurrentRead(Read read, TransactionSynchronization transaction) {
    }

    private final AiControlConfigurationSource source;
    private final Scheduler scheduler;

    public ControlConfigurationReader(AiControlConfigurationSource source, Scheduler scheduler) {
        this.source = Objects.requireNonNull(source);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public Mono<ControlConfiguration> read(ExecutionContext context) {
        return Mono.deferContextual(values -> {
            // 新的受理／派发准备优先，不能被外层只读范围中的旧配置覆盖。
            var prepared = ControlPreparation.find(values, context);
            if (prepared.isPresent()) return Mono.just(prepared.get());
            var snapshot = ReadOperationSnapshot.find(values, context);
            if (snapshot.isPresent()) {
                if (snapshot.get().configuration() == null) {
                    return Mono.error(new IllegalStateException("Read operation did not request control configuration"));
                }
                return Mono.just(snapshot.get().configuration());
            }
            if (values.hasKey(KEY)) {
                Read read = values.get(KEY);
                if (!read.context().equals(context) || read.sourceKey() != sourceKey()) {
                    return Mono.error(new AccessDeniedException("Control read context or source mismatch"));
                }
                return Mono.just(read.configuration());
            }
            return Mono.fromCallable(() -> source.configuration(context)).subscribeOn(scheduler);
        });
    }

    public <T> Mono<T> inSnapshot(ExecutionContext context, Function<ControlConfiguration, Mono<T>> work) {
        return read(context).flatMap(configuration -> Mono.defer(() -> work.apply(configuration))
                .contextWrite(values -> values.put(KEY, new Read(context, configuration, sourceKey()))));
    }

    private Object sourceKey() {
        return source;
    }

    /**
     * 仅在一次同步事务门控回调内共享当前事实；不读取 Reactor 展示快照，不跨命令缓存允许结论。
     */
    public <T> T inCurrentTransaction(ExecutionContext context, Function<ControlConfiguration, T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Actual writable transaction is required");
        CurrentRead previous = CURRENT.get();
        if (previous != null) {
            if (!TransactionSynchronizationManager.getSynchronizations().contains(previous.transaction())
                    || !previous.read().context().equals(context) || previous.read().sourceKey() != sourceKey())
                throw new org.springframework.security.access.AccessDeniedException("Transaction scope or source mismatch");
            return work.apply(previous.read().configuration());
        }
        var configuration = source.configurationInCurrentTransaction(context);
        var transaction = new TransactionSynchronization() {
        };
        TransactionSynchronizationManager.registerSynchronization(transaction);
        CURRENT.set(new CurrentRead(new Read(context, configuration, sourceKey()), transaction));
        try {
            return work.apply(configuration);
        } finally {
            CURRENT.remove();
        }
    }
}
