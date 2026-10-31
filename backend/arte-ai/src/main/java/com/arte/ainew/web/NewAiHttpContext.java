package com.arte.ainew.web;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.core.diagnostics.RequestObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

/**
 * HTTP 上下文管理：创建、读取、准备
 * <p>
 * HTTP 入口显式选择所需权限，校验权限并委托给 ExecutionContextFactory 创建出所需上下文。
 * 必须在 MVC 请求线程调用，身份与范围由授权解析器验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 16:28 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public final class NewAiHttpContext {

    private final ExecutionContextFactory executionContextFactory;
    private final NewAiProperties properties;

    public NewAiHttpContext(ExecutionContextFactory executionContextFactory, NewAiProperties properties) {
        this.executionContextFactory = executionContextFactory;
        this.properties = properties;
    }

    /**
     * 不绑定幂等键，使用当前 MVC 身份的读取上下文执行只读操作。
     * <p>
     * 等同于向带幂等键的重载传入 {@code null}，必须在 MVC 请求线程调用。
     *
     * @param scope                请求的租户和工作空间范围
     * @param scopes               可信 HTTP 入口申请的权限范围，由授权解析器验证
     * @param timeout              请求超时，至少一秒且不超过平台配置的最大时长
     * @param includeConfiguration 是否在读取快照中包含配置（解析器支持读取快照时生效）
     * @param work                 使用已验证上下文执行的只读操作
     * @param <T>                  操作结果类型
     * @return 携带当前请求观测信息的操作结果流
     */
    public <T> Mono<T> read(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                            boolean includeConfiguration, Function<ExecutionContext, Mono<T>> work) {
        return read(scope, scopes, timeout, includeConfiguration, null, work);
    }

    /**
     * 在创建读取快照时固定幂等键，调用链不能改写已绑定的授权上下文。
     * 校验请求范围和超时，使用当前 MVC 身份的读取上下文执行只读操作。
     * <p>
     * 必须在 MVC 请求线程调用，以捕获当前身份、跟踪 ID 和请求观测信息。
     * 支持读取快照的解析器按订阅创建快照，否则使用普通上下文创建流程。
     * 幂等键在构造上下文请求时固定，调用链不能改写已绑定的授权上下文；不绑定预算引用。
     *
     * @param scope                请求的租户和工作空间范围
     * @param scopes               可信 HTTP 入口申请的权限范围，由授权解析器验证
     * @param timeout              请求超时，至少一秒且不超过平台配置的最大时长
     * @param includeConfiguration 是否在读取快照中包含配置（解析器支持读取快照时生效）
     * @param idempotencyKey       可选的业务幂等键，普通读取可传 {@code null}
     * @param work                 使用已验证上下文执行的只读操作
     * @param <T>                  操作结果类型
     * @return 携带当前请求观测信息的操作结果流
     */
    public <T> Mono<T> read(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                            boolean includeConfiguration, String idempotencyKey, Function<ExecutionContext, Mono<T>> work) {
        validate(scope, timeout, null, idempotencyKey);
        ExecutionContextRequest contextRequest = new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(), scopes,
                timeout, RequestObservation.currentTraceId(), null, properties.releaseRef(), idempotencyKey);
        return RequestObservation.observe(executionContextFactory.readCurrent(contextRequest, includeConfiguration, work));
    }

    /**
     * 校验请求参数，并根据当前 MVC 身份创建执行上下文。
     * <p>
     * 必须在 MVC 请求线程调用，身份、跟踪 ID 及上下文截止时间在调用时确定，授权在订阅时解析。
     * 发布引用取自服务端配置；预算引用和幂等键传入上下文，其业务有效性由后续服务校验。
     *
     * @param scope          请求的租户和工作空间范围
     * @param scopes         可信 HTTP 入口申请的权限范围，由授权解析器验证
     * @param timeout        上下文有效时长，至少一秒且不超过平台配置的最大时长
     * @param budgetRef      可选的预算账户引用
     * @param idempotencyKey 可选的业务幂等键
     * @return 携带当前请求观测信息的执行上下文流
     */
    public Mono<ExecutionContext> create(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                                         String budgetRef, String idempotencyKey) {
        validate(scope, timeout, budgetRef, idempotencyKey);
        ExecutionContextRequest contextRequest = new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(), scopes,
                timeout, RequestObservation.currentTraceId(), budgetRef, properties.releaseRef(), idempotencyKey);
        return RequestObservation.observe(executionContextFactory.createCurrent(contextRequest));
    }

    /**
     * 校验请求参数，为当前 MVC 身份准备执行上下文并执行操作。
     * <p>
     * 必须在 MVC 请求线程调用，以捕获当前身份、跟踪 ID 和请求观测信息。
     * 支持准备能力的解析器在每次订阅时提供当前资格和配置，否则使用普通上下文创建流程；
     * 请求超时覆盖上下文准备和操作执行。发布引用取自服务端配置。
     *
     * @param scope          请求的租户和工作空间范围
     * @param scopes         可信 HTTP 入口申请的权限范围，由授权解析器验证
     * @param timeout        请求超时，至少一秒且不超过平台配置的最大时长
     * @param budgetRef      可选的预算账户引用，业务有效性由后续服务校验
     * @param idempotencyKey 可选的业务幂等键，幂等仲裁由受理服务负责
     * @param work           使用已验证上下文及可用准备配置执行的操作
     * @param <T>            操作结果类型
     * @return 携带当前请求观测信息的操作结果流
     */
    public <T> Mono<T> prepare(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                               String budgetRef, String idempotencyKey, Function<ExecutionContext, Mono<T>> work) {
        validate(scope, timeout, budgetRef, idempotencyKey);
        ExecutionContextRequest contextRequest = new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(), scopes,
                timeout, RequestObservation.currentTraceId(), budgetRef, properties.releaseRef(), idempotencyKey);
        return RequestObservation.observe(executionContextFactory.prepareCurrent(contextRequest, work));
    }

    private void validate(ConversationRequests.Scope scope, Duration timeout, String budgetRef, String idempotencyKey) {
        ContractChecks.id(scope.tenantId(), "tenantId");
        ContractChecks.id(scope.workspaceId(), "workspaceId");
        ContractChecks.optionalId(budgetRef, "budgetRef");
        ContractChecks.optionalId(idempotencyKey, "idempotencyKey");
        ContractChecks.require(timeout != null && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                && timeout.compareTo(properties.limits().maximumTimeout()) <= 0, "Timeout exceeds execution limits");
    }

}
