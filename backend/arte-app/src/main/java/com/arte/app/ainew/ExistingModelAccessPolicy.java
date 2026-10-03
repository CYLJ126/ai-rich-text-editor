package com.arte.app.ainew;

import com.arte.ai.model.generation.ModelPlan;
import com.arte.ai.spi.security.ModelAccessPolicy;
import com.arte.app.security.bridge.JdbcSecurityRepository;
import com.arte.base.execution.ExecutionFailures;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.security.CommonResourceAction;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 模型是独立领域，复用当前账号／成员／任务／应用策略，不把模型伪装成文章资源。
 */
// 聊天受理使用 READ_COMMITTED；工作线程在外发前独立重新鉴权，不继承提交线程事务。
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS, isolation = Isolation.READ_COMMITTED)
public class ExistingModelAccessPolicy implements ModelAccessPolicy {
    private final JdbcSecurityRepository repository;
    private final Clock clock;
    private final String applicationId;

    public ExistingModelAccessPolicy(JdbcSecurityRepository repository, Clock clock, String applicationId) {
        this.repository = repository;
        this.clock = clock;
        this.applicationId = applicationId;
    }

    public void requireAllowed(ExecutionContext context, ModelPlan plan) {
        String action = CommonResourceAction.AI_PROCESS.code();
        if (context.isExpiredAt(clock.instant()))
            throw ExecutionFailures.beforeStart(CommonErrorCode.DEADLINE_EXCEEDED, context, "model-access");
        boolean allowed;
        try {
            var task = repository.task(context).orElse(null);
            allowed = context.authorizationScopes().contains(action) && context.scope().equals(plan.binding().scope())
                    && repository.account(context.scope().principal()).filter(JdbcSecurityRepository.Account::enabled).isPresent()
                    && repository.membership(context).filter(JdbcSecurityRepository.Membership::enabled).isPresent()
                    && task != null && task.enabled() && clock.instant().isBefore(task.validUntil())
                    && applicationId.equals(task.applicationId()) && plan.binding().ref().definitionId().equals(task.bindingId())
                    && repository.taskAction(context, context.scope().principal(), action).filter(JdbcSecurityRepository.Flag::enabled).isPresent()
                    && repository.applicationPolicy(context, task, action).filter(p -> p.applicationEnabled() && p.bindingEnabled()).isPresent()
                    && repository.connection(context, plan.connection().ref().resource()).filter(c -> c.enabled() && c.origin().equals(plan.destination().origin().toASCIIString())).isPresent();
        } catch (RuntimeException unavailable) {
            throw ExecutionFailures.beforeStart(CommonErrorCode.POLICY_UNAVAILABLE, context, "model-access");
        }
        if (!allowed) throw ExecutionFailures.beforeStart(CommonErrorCode.UNAUTHORIZED, context, "model-access");
    }
}
