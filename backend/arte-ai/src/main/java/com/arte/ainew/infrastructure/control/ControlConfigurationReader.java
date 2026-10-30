package com.arte.ainew.infrastructure.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ControlPreparation;
import com.arte.ainew.context.ReadOperationSnapshot;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Objects;
import java.util.function.Function;

/**
 * 迁移期配置桥
 * <p>
 * 供来源适配与兼容门面使用，读取快照不参与写入授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public final class ControlConfigurationReader {

    private static final Object KEY = new Object();

    private record Read(ExecutionContext context, NewAiProperties configuration, Object sourceKey) {
    }

    private final NewAiProperties fixed;
    private final AiControlConfigurationSource source;
    private final Scheduler scheduler;

    public ControlConfigurationReader(NewAiProperties fixed) {
        this.fixed = Objects.requireNonNull(fixed);
        this.source = null;
        this.scheduler = null;
    }

    public ControlConfigurationReader(AiControlConfigurationSource source, Scheduler scheduler) {
        this.fixed = null;
        this.source = Objects.requireNonNull(source);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public Mono<NewAiProperties> read(ExecutionContext context) {
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
            if (source == null) {
                assert fixed != null;
                return Mono.just(fixed);
            } else {
                assert scheduler != null;
                return Mono.fromCallable(() -> source.configuration(context)).subscribeOn(scheduler);
            }
        });
    }

    public <T> Mono<T> inSnapshot(ExecutionContext context, Function<NewAiProperties, Mono<T>> work) {
        return read(context).flatMap(configuration -> Mono.defer(() -> work.apply(configuration))
                .contextWrite(values -> values.put(KEY, new Read(context, configuration, sourceKey()))));
    }

    private Object sourceKey() {
        return source == null ? fixed : source;
    }
}
