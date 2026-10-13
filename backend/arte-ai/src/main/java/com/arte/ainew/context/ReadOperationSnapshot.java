package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.function.Function;

/**
 * 仅由上下文工厂安装的订阅内快照；不改变持久化 ExecutionContext，不向 SSE 后续订阅传播。
 */
public final class ReadOperationSnapshot {
    private static final Object KEY = new Object();

    private record Bound(ExecutionContext context, ExecutionReadAuthorizationResolver.Snapshot snapshot) {
    }

    private ReadOperationSnapshot() {
    }

    static <T> Mono<T> use(ExecutionContext context, ExecutionReadAuthorizationResolver.Snapshot snapshot,
                           Function<ExecutionContext, Mono<T>> work) {
        return Mono.defer(() -> work.apply(context)).contextWrite(values -> values.put(KEY, new Bound(context, snapshot)));
    }

    public static Optional<ExecutionReadAuthorizationResolver.Snapshot> find(ContextView values, ExecutionContext context) {
        if (!values.hasKey(KEY)) return Optional.empty();
        Bound bound = values.get(KEY);
        if (!bound.context().equals(context)) throw new AccessDeniedException("Read snapshot context mismatch");
        return Optional.of(bound.snapshot());
    }
}
