package com.arte.app.ainew;

import com.arte.ai.model.definition.DefinitionRef;

import java.util.List;

/**
 * 面向界面的初始化白名单；不包含凭据、环境变量名称或内部执行上下文。
 */
public record NewChatBootstrap(boolean enabled, String unavailableReason, List<Workspace> workspaces,
                               Model defaultModel) {
    public NewChatBootstrap {
        workspaces = List.copyOf(workspaces);
    }

    public record Workspace(String tenantId, String workspaceId, List<String> allowedActions) {
        public Workspace {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    public record Model(String name, String providerId, DefinitionRef bindingRef, String destination,
                        String purpose, List<String> inputTypes, boolean streaming,
                        int contextMaxBytes, int maxOutputTokens, boolean retrievalEnabled) {
        public Model(String name, String providerId, DefinitionRef bindingRef, String destination, String purpose,
                     List<String> inputTypes, boolean streaming, int contextMaxBytes, int maxOutputTokens) {
            this(name, providerId, bindingRef, destination, purpose, inputTypes, streaming, contextMaxBytes, maxOutputTokens, false);
        }
        public Model {
            inputTypes = List.copyOf(inputTypes);
        }
    }

    public static NewChatBootstrap disabled() {
        return new NewChatBootstrap(false, "CHAT_DISABLED", List.of(), null);
    }
}
