package com.arte.ai.pojo.tool;

/**
 * 工具任务恢复请求，令牌放在请求体中，避免出现在 URL 和访问日志。
 */
public record ToolResumeRequest(String resumeToken) {
    public ToolResumeRequest {
        if (resumeToken == null || resumeToken.isBlank()) {
            throw new IllegalArgumentException("resumeToken must not be blank");
        }
    }
}
