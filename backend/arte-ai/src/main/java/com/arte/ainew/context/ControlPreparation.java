package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.function.Function;

/**
 * 在响应式订阅内将控制配置绑定到一个执行上下文。
 * <p>
 * 准备结果不持久化，也不能替代新的写授权检查。
 * 受理和派发边界必须显式重新准备；仅可信服务端代码可安装从当前授权来源取得的配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 16:55 ✾
 */
public final class ControlPreparation {

    private static final Object KEY = new Object();

    private record Bound(ExecutionContext context,
                         com.arte.ainew.spi.auth.AiControlConfigurationSource.PreparedConfiguration prepared) {
    }

    private ControlPreparation() {
    }

    /**
     * 为指定操作的订阅安装可信准备快照。
     *
     * @param context       快照绑定的执行上下文
     * @param configuration 已由当前授权来源验证的配置
     * @param work          操作，其上游响应式调用可复用本次准备结果
     * @param <T>           操作结果类型
     * @return 延迟执行的操作，准备结果绑定在其 Reactor 上下文中
     */
    public static <T> Mono<T> use(ExecutionContext context, NewAiProperties configuration,
                                  Function<ExecutionContext, Mono<T>> work) {
        return use(context, new com.arte.ainew.spi.auth.AiControlConfigurationSource.PreparedConfiguration(configuration, null), work);
    }

    /**
     * 安装包含发布版本凭证的准备结果；凭证不持久化、不跨操作复用。
     */
    public static <T> Mono<T> use(ExecutionContext context,
                                  com.arte.ainew.spi.auth.AiControlConfigurationSource.PreparedConfiguration prepared,
                                  Function<ExecutionContext, Mono<T>> work) {
        return Mono.defer(() -> work.apply(context))
                .contextWrite(values -> values.put(KEY, new Bound(context, prepared)));
    }

    /**
     * 在当前订阅中查找与指定执行上下文完全匹配的准备结果。
     *
     * @param values  当前 Reactor 上下文
     * @param context 请求准备配置的执行上下文
     * @return 准备配置；本次订阅没有准备结果时返回空
     * @throws AccessDeniedException 已安装快照属于其他执行上下文时抛出
     */
    public static Optional<NewAiProperties> find(ContextView values, ExecutionContext context) {
        return findPrepared(values, context).map(com.arte.ainew.spi.auth.AiControlConfigurationSource.PreparedConfiguration::configuration);
    }

    /**
     * 按完整执行上下文取得当前订阅的配置及版本凭证，供发送前复核使用。
     */
    public static Optional<com.arte.ainew.spi.auth.AiControlConfigurationSource.PreparedConfiguration> findPrepared(ContextView values, ExecutionContext context) {
        if (!values.hasKey(KEY)) return Optional.empty();
        Bound bound = values.get(KEY);
        if (!bound.context().equals(context))
            throw new AccessDeniedException("Preparation context mismatch");
        return Optional.of(bound.prepared());
    }
}
