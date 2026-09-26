package com.arte.ai.api.tool.security;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.pojo.tool.ToolAuthorizationDecision;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolInvocation;

import java.util.concurrent.CompletionStage;

/**
 * 在调用执行前校验主体是否具备使用工具及其资源的权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolAuthorizer {

    CompletionStage<ToolAuthorizationDecision> authorize(ToolDefinition definition, ToolInvocation<? extends ToolRequest> invocation);
}
