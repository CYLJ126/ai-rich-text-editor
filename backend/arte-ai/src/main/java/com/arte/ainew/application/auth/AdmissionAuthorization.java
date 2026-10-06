package com.arte.ainew.application.auth;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiProperties.Grant;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 应用边界当前授权检查；任务权限上限不可扩大，发布／绑定／预算使用资格来自受信配置。
 * <p>
 * 典型用法是先检查操作权限，再检查资源使用范围：
 * authorization.require(context, AdmissionAuthorization.INVOKE)
 *         .map(current -> {
 *             var grant = authorization.grant(current);
 *             // 再检查 grant.bindingIds() 是否包含目标绑定
 *             return grant;
 *         });
 * 例如，一个主体拥有 ai:invoke，说明它可以提交 AI 调用；但能调用哪个绑定、使用哪个预算，还要根据 grant 中的配置继续判断。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class AdmissionAuthorization {
    /**
     * AI 调用权限：允许发现／解析可用能力、绑定和连接，组装输入上下文并提交调用。
     * 关联会话的提交还需 CONVERSATION 权限，实际可用绑定和预算仍由主体授权配置限制。
     */
    public static final String INVOKE = "ai:invoke";

    /**
     * 会话操作权限：允许创建会话、读取所属会话／Turn，以及将调用关联到会话。
     * 不授予其他主体会话的访问权，也不单独授予 AI 调用权限。
     */
    public static final String CONVERSATION = "ai:conversation";

    /**
     * 执行数据读取权限：当前用于读取所属上下文快照，不触发模型调用。
     * 会话／Turn 读取使用 CONVERSATION 权限；执行状态和结果查询服务后续接入。
     */
    public static final String READ = "ai:read";

    /**
     * 预算管理权限：当前用于显式初始化授权范围内、归属于当前主体的预算账户。
     * 独立于调用权限，不允许重置已有账本或管理其他主体账户。
     */
    public static final String BUDGET_ADMIN = "ai:budget:admin";

    private final ExecutionAuthorizationResolver resolver;

    private final NewAiProperties properties;

    private final Clock clock;

    public AdmissionAuthorization(ExecutionAuthorizationResolver resolver, NewAiProperties properties, Clock clock) {
        this.resolver = Objects.requireNonNull(resolver);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 操作权限检查
     * <p>
     * 不只是检查一个权限字符串，也能发现授权被撤销或主体发生变化。授权查询受剩余执行时间限制。
     *
     * @param context 执行上下文
     * @param scope 操作权限范围
     * @return 执行上下文
     */
    public Mono<ExecutionContext> require(ExecutionContext context, String scope) {
        return Mono.defer(() -> {
            var original = Objects.requireNonNull(context).authorization();
            // 检查上下文是否包含所需权限，例如 ai:invoke
            if (!original.scopes().contains(scope)) {
                throw new AccessDeniedException("Execution scope denied");
            }
            // 检查执行期限是否已过期
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
            }
            // 通过 ExecutionAuthorizationResolver 重新查询当前授权，确认授权仍有效
            return resolver.resolve(original.principal().subjectName(), original.tenantId(), original.workspaceId(), original.scopes())
                    .timeout(remaining)
                    .switchIfEmpty(Mono.error(new AccessDeniedException("Execution authorization denied")))
                    .map(fresh -> {
                        // 确认主体、租户、工作空间和权限集合与原上下文一致
                        if (!fresh.principal().equals(original.principal()) || !fresh.tenantId().equals(original.tenantId())
                                || !fresh.workspaceId().equals(original.workspaceId()) || !fresh.scopes().equals(original.scopes())) {
                            throw new AccessDeniedException("Execution authorization changed");
                        }
                        if (!clock.instant().isBefore(context.deadline())) {
                            throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
                        }
                        // 返回更新了授权信息的上下文，保留原执行 ID、期限、预算等信息
                        return new ExecutionContext(context.executionId(), context.traceId(), fresh, context.deadline(),
                                context.parentExecutionId(), context.budgetRef(), context.releaseRef(), context.idempotencyKey());
                    });
        });
    }

    /**
     * 固定授权配置查询，直接返回 NewAiProperties.Grant
     * <p>
     * 根据上下文中的主体 ID、名称、类型、租户和工作空间，查找处于启用状态的配置；找不到就抛出 AccessDeniedException。
     * 调用方据此判断该主体能否使用某个绑定或预算。grant 本身不重新查询授权，也不检查操作权限或执行期限。
     *
     * @param context
     * @return 授权配置（包含 bindingIds、budgetRefs 等信息）
     */
    public Grant grant(ExecutionContext context) {
        var owner = ExecutionOwner.from(context);
        return properties.grants().stream().filter(g -> g.enabled() && g.subjectId().equals(owner.subjectId())
                        && g.tenantId().equals(owner.tenantId()) && g.workspaceId().equals(owner.workspaceId())
                        && g.subjectName().equals(context.authorization().principal().subjectName())
                        && g.principalKind() == context.authorization().principal().kind()).findFirst()
                .orElseThrow(() -> new AccessDeniedException("Configuration scope denied"));
    }
}
