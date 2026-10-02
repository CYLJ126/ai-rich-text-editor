package com.arte.app.ainew;

import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.AcceptedExecution;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 独立新路径，旧聊天请求和前端暂不切换。
 */
@RestController
@RequestMapping("/api/ai-new/model")
@ConditionalOnProperty(name = "arte.ai-new.model.enabled", havingValue = "true")
public class NewModelController {
    public record TextMessage(MessageRole role, String text) {
        public TextMessage {
            role = com.arte.base.validation.ContractChecks.required(role, "role");
            text = com.arte.base.validation.ContractChecks.required(text, "text");
        }
    }

    public record Submission(String tenantId, String workspaceId, List<TextMessage> messages, ModelOptions options,
                             boolean externalTransferConfirmed) {
        public Submission {
            tenantId = com.arte.base.validation.ContractChecks.identifier(tenantId, "tenantId");
            workspaceId = com.arte.base.validation.ContractChecks.identifier(workspaceId, "workspaceId");
            messages = List.copyOf(com.arte.base.validation.ContractChecks.required(messages, "messages"));
            if (messages.isEmpty() || messages.size() > 128)
                throw new IllegalArgumentException("invalid message count");
        }

        GenerationRequest generation() {
            return new GenerationRequest(messages.stream().map(m -> new Message(m.role(), List.of(new TextPart(m.text())))).toList(),
                    options == null ? new ModelOptions(null, null) : options, List.of(), null);
        }
    }

    private final NewModelCallService service;

    public NewModelController(NewModelCallService service) {
        this.service = service;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/generate")
    public ResponseEntity<AcceptedExecution> generate(HttpServletRequest http, @RequestHeader("Idempotency-Key") String key, @RequestBody Submission input) {
        return ResponseEntity.accepted().body(service.generate(http, input.tenantId(), input.workspaceId(), key, input.generation(), input.externalTransferConfirmed()));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/executions/{id}")
    public Object find(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.coordinator().find(service.viewer(http, tenantId, workspaceId), id);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/executions/{id}/events")
    public Object events(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId,
                         @RequestParam(defaultValue = "-1") long after, @RequestParam(defaultValue = "50") int limit) {
        return service.coordinator().events(service.viewer(http, tenantId, workspaceId), id, after, limit);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/executions/{id}/cancel")
    public Object cancel(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return Map.of("status", service.coordinator().cancel(service.viewer(http, tenantId, workspaceId), id));
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ExecutionError> failure(BaseException failure) {
        String code = failure.error().code();
        int status = code.equals(CommonErrorCode.UNAUTHORIZED.code()) ? 403
                : code.equals(CommonErrorCode.NOT_FOUND.code()) ? 404 : code.equals(CommonErrorCode.IDEMPOTENCY_CONFLICT.code()) || code.equals(CommonErrorCode.VERSION_CONFLICT.code()) ? 409
                : code.equals(CommonErrorCode.INVALID_ARGUMENT.code()) || code.equals(CommonErrorCode.UNSUPPORTED.code()) ? 400
                : code.equals(CommonErrorCode.BUSY.code()) || code.equals(CommonErrorCode.RATE_LIMITED.code()) ? 429
                : code.equals(CommonErrorCode.DEADLINE_EXCEEDED.code()) ? 504 : 503;
        return ResponseEntity.status(status).body(failure.error());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ExecutionError> denied() {
        return ResponseEntity.status(403).body(ExecutionError.of(CommonErrorCode.UNAUTHORIZED, "identity", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ExecutionError> unavailable() {
        return ResponseEntity.status(503).body(ExecutionError.of(CommonErrorCode.OUTCOME_UNKNOWN, "model-store", false, SideEffectStatus.UNKNOWN, ResultCertainty.UNKNOWN, null));
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ExecutionError> invalid(Exception failure) {
        return ResponseEntity.badRequest().body(ExecutionError.of(CommonErrorCode.INVALID_ARGUMENT, "input", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }
}
