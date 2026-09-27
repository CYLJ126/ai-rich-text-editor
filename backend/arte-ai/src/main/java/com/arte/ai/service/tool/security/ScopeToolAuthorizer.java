package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.api.tool.security.ToolAuthorizer;
import com.arte.ai.pojo.tool.ToolAuthorizationDecision;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 校验工具风险画像声明的最小 Scope。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
public class ScopeToolAuthorizer implements ToolAuthorizer {

    @Override
    public CompletionStage<ToolAuthorizationDecision> authorize(
            ToolDefinition definition, ToolInvocation<? extends ToolRequest> invocation) {
        Set<String> missing = new HashSet<>(definition.riskProfile().requiredScopes());
        missing.removeAll(invocation.context().principal().scopes());
        return CompletableFuture.completedFuture(missing.isEmpty()
                ? new ToolAuthorizationDecision(true, "required scopes are present", Set.of())
                : new ToolAuthorizationDecision(false, "missing required scopes", missing));
    }
}
