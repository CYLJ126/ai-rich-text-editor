package com.arte.app.ainew;

import com.arte.app.security.bridge.ExistingIdentityAdapter;
import com.arte.app.security.bridge.JdbcSecurityRepository;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读取当前身份与配置对应的许可，不登记任务、同意或预算，不探测供应商。
 */
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class NewChatBootstrapService {
    private final ExistingIdentityAdapter identity;
    private final JdbcSecurityRepository repository;
    private final ConfiguredModelDefinitions definitions;
    private final String application, modelName;
    private final int contextBytes, outputTokens;
    private final boolean streaming;

    public NewChatBootstrapService(ExistingIdentityAdapter identity, JdbcSecurityRepository repository,
                                   ConfiguredModelDefinitions definitions, String application, String modelName,
                                   int contextBytes, int outputTokens) {
        this(identity, repository, definitions, application, modelName, contextBytes, outputTokens, false);
    }

    public NewChatBootstrapService(ExistingIdentityAdapter identity, JdbcSecurityRepository repository, ConfiguredModelDefinitions definitions,
                                   String application, String modelName, int contextBytes, int outputTokens, boolean streaming) {
        this.streaming = streaming;
        this.identity = identity;
        this.repository = repository;
        this.definitions = definitions;
        this.application = application;
        this.modelName = modelName;
        this.contextBytes = contextBytes;
        this.outputTokens = outputTokens;
    }

    public NewChatBootstrap read(HttpServletRequest request) {
        var account = identity.requireAccount(request);
        var scope = definitions.configuredScope(account.principal());
        if (repository.membership(scope.tenantId(), scope.workspaceId(), scope.principal()).filter(JdbcSecurityRepository.Membership::enabled).isEmpty()
                || !allowed(scope.tenantId(), scope.workspaceId(), CommonResourceAction.AI_PROCESS.code()))
            return new NewChatBootstrap(true, "NO_ACCESS", List.of(), null);
        var actions = new ArrayList<String>();
        actions.add(CommonResourceAction.AI_PROCESS.code());
        if (allowed(scope.tenantId(), scope.workspaceId(), CommonResourceAction.EGRESS.code()))
            actions.add(CommonResourceAction.EGRESS.code());
        var binding = definitions.binding(scope, definitions.bindingRef()).orElseThrow();
        var connection = definitions.connection(binding.connectionRef()).orElseThrow();
        var model = new NewChatBootstrap.Model(modelName, connection.providerId(), binding.ref(),
                connection.endpoint().toASCIIString(), "model.generate", List.of("text"), streaming, contextBytes, outputTokens);
        return new NewChatBootstrap(true, null,
                List.of(new NewChatBootstrap.Workspace(scope.tenantId(), scope.workspaceId(), actions)), model);
    }

    private boolean allowed(String tenant, String workspace, String action) {
        return repository.applicationPolicy(tenant, workspace, application, definitions.bindingRef().definitionId(), action)
                .filter(p -> p.applicationEnabled() && p.bindingEnabled()).isPresent();
    }
}
