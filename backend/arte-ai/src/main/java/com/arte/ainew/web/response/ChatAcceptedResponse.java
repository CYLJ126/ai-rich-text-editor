package com.arte.ainew.web.response;

import com.arte.ainew.common.execution.AcceptedExecution;

import java.time.Instant;

/**
 * 耐久受理回执，不表示模型已完成；轮次 ID 后续通过会话轮次查询取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 16:28 ✾
 */
public record ChatAcceptedResponse(String invocationId, String conversationId, AcceptedExecution.Kind kind,
                                   Instant acceptedAt) {
    public static ChatAcceptedResponse from(String conversationId, AcceptedExecution accepted) {
        return new ChatAcceptedResponse(accepted.executionId(), conversationId, accepted.kind(), accepted.acceptedAt());
    }
}
