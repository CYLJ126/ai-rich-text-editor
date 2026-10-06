package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.spi.persistence.ExecutionEventStore;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 结果及单页事件读取，均重新授权；只使用权威结果引用，不读取尚未提交的孤立字节。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public final class DefaultExecutionEventService implements ExecutionEventService {
    private final AdmissionAuthorization authorization;
    private final ExecutionStore executions;
    private final ExecutionEventStore events;
    private final ExecutionResultStore results;

    public DefaultExecutionEventService(AdmissionAuthorization authorization, ExecutionStore executions,
                                        ExecutionEventStore events, ExecutionResultStore results) {
        this.authorization = authorization;
        this.executions = executions;
        this.events = events;
        this.results = results;
    }

    @Override
    public Mono<StoreOutcome<ExecutionEventStore.Page>> replay(ExecutionEvent.Cursor cursor, int limit, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current ->
                executions.find(ExecutionOwner.from(current), cursor.executionId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMap(ignored -> events.replay(ExecutionOwner.from(current), cursor, limit)));
    }

    @Override
    public Flux<ExecutionEvent<?>> watch(ExecutionEvent.Cursor cursor, ExecutionContext context) {
        return Flux.error(new AdmissionException(ResultCodeEnum.AI_EVENT_WATCH_NOT_ENABLED));
    }

    @Override
    public Mono<InvocationResult> result(String invocationId, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
            var owner = ExecutionOwner.from(current);
            return executions.find(owner, invocationId)
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                    .flatMap(invocation -> {
                        if (invocation.result() == null) {
                            return Mono.error(new AdmissionException(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE));
                        }
                        return results.find(owner, invocationId, invocation.result())
                                .switchIfEmpty(Mono.error(new IllegalStateException("Committed result bytes are missing")));
                    });
        });
    }
}
