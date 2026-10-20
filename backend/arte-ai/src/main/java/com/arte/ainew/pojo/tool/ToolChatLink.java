package com.arte.ainew.pojo.tool;

/**
 * Run 在聊天中的不可变归属；finalInvocationId 仅在最终完整回答提交时设置，始终是真实模型子调用 ID。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public record ToolChatLink(String runId, String conversationId, String turnId, long conversationVersion,
                           String finalInvocationId) {
}
