package com.arte.app.security.bridge;

import org.springframework.security.access.AccessDeniedException;

/** 新入口的应用动作拒绝；稳定阶段用于界面提示，具体策略原因仅记录在服务端。 */
public final class ExecutionAccessDeniedException extends AccessDeniedException {
    private final String failureStage;

    public ExecutionAccessDeniedException(String failureStage) {
        super("arte.security.scope_or_application_rejected");
        this.failureStage = failureStage;
    }

    public String failureStage() {
        return failureStage;
    }
}
