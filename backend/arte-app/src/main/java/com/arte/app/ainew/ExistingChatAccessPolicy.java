package com.arte.app.ainew;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.spi.security.ChatAccessPolicy;
import com.arte.app.security.bridge.JdbcSecurityRepository;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.security.CommonResourceAction;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 聊天元数据复用真实账号、成员和应用／绑定许可；模型结果仍重新核对模型权限。
 */
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class ExistingChatAccessPolicy implements ChatAccessPolicy {
    private final JdbcSecurityRepository repository;
    private final ConfiguredModelDefinitions definitions;
    private final String application;
    private final Clock clock;

    public ExistingChatAccessPolicy(JdbcSecurityRepository repository, ConfiguredModelDefinitions definitions, String application, Clock clock) {
        this.repository = repository;
        this.definitions = definitions;
        this.application = application;
        this.clock = clock;
    }

    @Override
    public void requireAllowed(ExecutionContext viewer, DefinitionRef binding) {
        if (viewer.isExpiredAt(clock.instant()))
            throw ChatValues.failure(CommonErrorCode.DEADLINE_EXCEEDED, "chat-access");
        String action = CommonResourceAction.AI_PROCESS.code();
        boolean allowed;
        try {
            var task = repository.task(viewer).orElse(null);
            allowed = definitions.binding(viewer.scope(), binding).isPresent() && viewer.authorizationScopes().contains(action)
                    && repository.account(viewer.scope().principal()).filter(JdbcSecurityRepository.Account::enabled).isPresent()
                    && repository.membership(viewer).filter(JdbcSecurityRepository.Membership::enabled).isPresent()
                    && task != null && task.enabled() && clock.instant().isBefore(task.validUntil())
                    && application.equals(task.applicationId()) && binding.definitionId().equals(task.bindingId())
                    && repository.taskAction(viewer, viewer.scope().principal(), action).filter(JdbcSecurityRepository.Flag::enabled).isPresent()
                    && repository.applicationPolicy(viewer, task, action).filter(p -> p.applicationEnabled() && p.bindingEnabled()).isPresent();
        } catch (RuntimeException unavailable) {
            throw ChatValues.failure(CommonErrorCode.POLICY_UNAVAILABLE, "chat-access");
        }
        if (!allowed) throw ChatValues.failure(CommonErrorCode.UNAUTHORIZED, "chat-access");
    }
}
