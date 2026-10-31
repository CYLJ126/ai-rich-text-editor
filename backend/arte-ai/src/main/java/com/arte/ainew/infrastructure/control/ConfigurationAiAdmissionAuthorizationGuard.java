package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.ExecutionPolicyChecks;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.BudgetDefinition;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Set;
import java.util.function.Function;

/**
 * 独立预检与原写事务复核
 * <p>
 * 事务内当前配置仅在本次同步回调共享，不持锁等待网络。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public final class ConfigurationAiAdmissionAuthorizationGuard implements AiAdmissionAuthorizationGuard {

    private final ControlConfigurationReader reader;
    private final AdmissionAuthorization authorization;
    private final AiResourceEntitlementService entitlements;
    private final BudgetDefinitionProvider budgets;
    private final AiExecutionPolicyProvider policies;
    private final AiExecutionPolicy ceiling;
    private final Clock clock;

    public ConfigurationAiAdmissionAuthorizationGuard(ControlConfigurationReader reader, AdmissionAuthorization authorization,
                                                      AiResourceEntitlementService entitlements, BudgetDefinitionProvider budgets,
                                                      AiExecutionPolicyProvider policies, NewAiProperties.Limits ceiling, Clock clock) {
        this.reader = reader;
        this.authorization = authorization;
        this.entitlements = entitlements;
        this.budgets = budgets;
        this.policies = policies;
        this.ceiling = ExecutionPolicies.from(ceiling);
        this.clock = clock;
    }

    /**
     * 按本次执行边界完成异步授权预检，并生成绑定原请求的内存许可。
     * <p>
     * 检查请求的能力／绑定引用及所需输入快照，核验当前操作权限、模型使用资格、精确绑定和发布标识，
     * 再核验预算使用资格、原执行归属及费率匹配，最后按与部署上限取交集的政策检查输入、输出、期限及快照约束。
     * <p>
     * 受理／派发／Run 预检可复用本次可信准备；真正外发前通过 revalidate 重新核验当前资格。
     * 受理及派发写入还须调用许可的事务内复核方法；预算余额及费用预留由预算存储链路负责。
     *
     * @param check 原请求、精确绑定、输入快照、可选工具集合、执行边界及明确选择的预算
     * @return 预检通过后的请求绑定许可，包含事务复核回调及本次核验的连接；检查失败时返回错误
     */
    @Override
    public Mono<Permit> authorize(Check check) {
        return Mono.defer(() -> {
            var request = check.request();
            // 核对请求与已解析绑定的能力／绑定引用，并要求受理、派发和 Run 边界提供可信输入快照。
            if (!request.binding().equals(check.binding().definition())
                    || !request.capability().equals(check.binding().capability().definition())
                    || check.boundary() != Boundary.EXTERNAL_SEND && check.snapshot() == null) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT));
            }
            Function<ExecutionContext, Mono<Permit>> prepare = current -> reader.inSnapshot(current, configuration ->
                    entitlements.resolve(request.binding(), request.capability(), current).flatMap(entitlement -> {
                        // 确认当前获准使用的精确绑定未变化，且来源发布标识与原执行上下文一致。
                        if (!entitlement.binding().equals(check.binding()) || !configuration.releaseRef().equals(current.releaseRef())) {
                            return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
                        }
                        requireOutputLimit(entitlement, request);
                        return budgets.resolve(check.budgetRef(), current, BudgetDefinitionProvider.Operation.EXECUTE).flatMap(budget -> {
                            // 确认所选预算归属于原执行主体／空间，且使用模型绑定指定的固定费率。
                            if (!budget.owner().equals(ExecutionOwners.from(current)) || !budget.rate().equals(check.binding().rate())) {
                                return Mono.error(new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH));
                            }
                            // 读取本次适用政策并与部署上限取交集，复核输入、输出、执行期限和快照约束。
                            return policies.current(current).map(value -> {
                                // 借助 ExecutionPolicyChecks 对调用请求做共用政策检查
                                InvocationRequest<?> refreshed = new InvocationRequest<>(request.capability(), request.binding(),
                                        request.kind(), request.input(), request.options(), current);
                                ExecutionPolicyChecks.require(new Check(refreshed, check.binding(), check.snapshot(),
                                        check.tools(), check.boundary(), check.budgetRef()), value.intersect(ceiling), clock);
                                // 返回绑定原请求的内存许可，供实际写事务再次复核并取得本次已核验的连接。
                                return (Permit) new Permit() {
                                    public void requireInCurrentTransaction(InvocationRequest<?> actual) {
                                        require(check, entitlement, actual);
                                    }

                                    public ConnectionDefinition connection() {
                                        return entitlement.connection();
                                    }
                                };
                            });
                        });
                    }));
            return check.boundary() == Boundary.EXTERNAL_SEND
                    ? authorization.revalidate(request.context(), prepare)
                    : authorization.prepare(request.context(), Set.of(AdmissionAuthorization.INVOKE), false, prepare);
        });
    }

    /**
     * 在实际受理／派发的原可写事务中同步复核当前资格，拦截预检后发生的撤权、配置变化或政策收紧。
     * <p>
     * 先确认实际请求与预检请求完全一致，再通过事务当前读核验操作权限及原截止时间、发布标识、当前模型／连接资格、
     * 预算使用资格与归属／费率，最后重新检查当前有效政策；当前配置仅在本次同步回调内共享。
     * 检查失败交由原业务写事务回滚，事务内调用保持同步，不阻塞 Reactor 或等待网络。
     *
     * @param check       预检时捕获的原请求及检查材料
     * @param entitlement 预检取得的模型资格，用于比对当前精确绑定、连接及发布依赖
     * @param actual      实际提交的请求，必须与许可绑定的原请求一致
     * @throws IllegalStateException 当前没有实际可写事务，或事务同步未启用
     * @throws AccessDeniedException 请求不匹配，或当前身份、范围及操作资格校验失败
     * @throws AdmissionException    发布、预算、费率、输入快照、执行期限或政策检查失败
     */
    private void require(Check check, AiEntitlement entitlement, InvocationRequest<?> actual) {
        // 确认实际写入请求与预检请求完全一致，防止将同一许可用于其他请求。
        if (!check.request().equals(actual)) {
            throw new AccessDeniedException("Admission permit request mismatch");
        }
        reader.inCurrentTransaction(actual.context(), configuration -> {
            // 核验原截止时间及 ai:invoke 权限，并确认当前配置仍覆盖原上下文的全部权限。
            authorization.requireConfigurationScope(configuration, actual.context(), AdmissionAuthorization.INVOKE);
            // 确认当前发布标识仍与原执行上下文一致，避免执行期间切换发布依赖。
            if (!configuration.releaseRef().equals(actual.context().releaseRef())) {
                throw new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
            }
            // 在原事务中重新核验当前模型／连接使用资格及精确发布内容，与预检结果比对。
            entitlements.requireInCurrentTransaction(entitlement, actual.context());
            requireOutputLimit(entitlement, actual);
            // 在原事务中重新读取所选预算定义并核验当前执行使用资格。
            BudgetDefinition budget = budgets.resolveInCurrentTransaction(check.budgetRef(), actual.context(), BudgetDefinitionProvider.Operation.EXECUTE);
            // 确认当前预算仍属于原执行归属，且固定费率仍与模型绑定匹配。
            if (!budget.owner().equals(ExecutionOwners.from(actual.context())) || !budget.rate().equals(check.binding().rate())) {
                throw new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH);
            }
            // 按事务内取得的当前政策与部署上限的交集，再次复核原请求和输入快照的执行限制。
            ExecutionPolicyChecks.require(check, policies.inCurrentTransaction(actual.context()).intersect(ceiling), clock);
            return null;
        });
    }

    private static void requireOutputLimit(AiEntitlement entitlement, InvocationRequest<?> request) {
        if (entitlement.modelAccess() != null && entitlement.modelAccess().maxOutputTokens() != null
                && request.input() instanceof GenerationRequest generation
                && generation.options().maxOutputTokens() > entitlement.modelAccess().maxOutputTokens()) {
            throw new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        }
    }
}
