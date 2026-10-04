package com.arte.app.ainew;

import com.arte.app.security.bridge.ExecutionAccessDeniedException;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

/**
 * 新 AI 入口共享的稳定错误信封。
 */
final class NewAiErrorResponses {
    private NewAiErrorResponses() {
    }

    static ResponseEntity<ExecutionError> failure(BaseException failure) {
        String code = failure.error().code();
        int status = code.equals(CommonErrorCode.UNAUTHORIZED.code()) ? 403
                : code.equals(CommonErrorCode.NOT_FOUND.code()) ? 404 : code.equals(CommonErrorCode.IDEMPOTENCY_CONFLICT.code()) || code.equals(CommonErrorCode.VERSION_CONFLICT.code()) ? 409
                : code.equals(CommonErrorCode.INVALID_ARGUMENT.code()) || code.equals(CommonErrorCode.UNSUPPORTED.code()) ? 400
                : code.equals(CommonErrorCode.BUSY.code()) || code.equals(CommonErrorCode.RATE_LIMITED.code()) ? 429
                : code.equals(CommonErrorCode.DEADLINE_EXCEEDED.code()) ? 504 : 503;
        return ResponseEntity.status(status).body(failure.error());
    }

    static ResponseEntity<ExecutionError> denied() {
        return ResponseEntity.status(403).body(ExecutionError.of(CommonErrorCode.UNAUTHORIZED, "identity", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }

    static ResponseEntity<ExecutionError> denied(AccessDeniedException failure) {
        if (failure instanceof ExecutionAccessDeniedException access) {
            return ResponseEntity.status(403).body(ExecutionError.of(CommonErrorCode.UNAUTHORIZED,
                    access.failureStage(), false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
        }
        return denied();
    }

    static ResponseEntity<ExecutionError> unavailable() {
        return ResponseEntity.status(503).body(ExecutionError.of(CommonErrorCode.OUTCOME_UNKNOWN, "model-store", false, SideEffectStatus.UNKNOWN, ResultCertainty.UNKNOWN, null));
    }

    static ResponseEntity<ExecutionError> invalid() {
        return ResponseEntity.badRequest().body(ExecutionError.of(CommonErrorCode.INVALID_ARGUMENT, "input", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }
}
