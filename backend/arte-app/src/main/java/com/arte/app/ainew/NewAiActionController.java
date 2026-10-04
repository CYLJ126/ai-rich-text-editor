package com.arte.app.ainew;

import com.arte.ai.context.ContextCapacityException;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.ExecutionError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-new/actions")
@ConditionalOnProperty(name = "arte.ai-new.action.enabled", havingValue = "true")
@PreAuthorize("isAuthenticated()")
public class NewAiActionController {
    public record Submission(String tenantId, String workspaceId, String text, String requirements,
                             ModelOptions options, boolean externalTransferConfirmed, ResourceContextSelection target,
                             List<ResourceContextSelection> references, String expectedContextDigest) {
        public Submission(String tenant, String workspace, String text, String requirements, ModelOptions options, boolean confirmed) {
            this(tenant, workspace, text, requirements, options, confirmed, null, null, null);
        }
    }

    public record Regeneration(String tenantId, String workspaceId, ModelOptions options,
                               boolean externalTransferConfirmed) {
    }

    private final NewAiActionCallService service;
    private final AiActionEventStreams streams;

    public NewAiActionController(NewAiActionCallService service, AiActionEventStreams streams) {
        this.service = service;
        this.streams = streams;
    }

    @PostMapping("/{actionId}/executions")
    public Object submit(HttpServletRequest http, @PathVariable String actionId, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody Submission input) {
        var result = input.target() != null || input.references() != null && !input.references().isEmpty() || input.expectedContextDigest() != null
                ? service.submit(http, input.tenantId(), input.workspaceId(), actionId, input.text(), input.requirements(), input.target(), input.references(),
                input.options(), input.expectedContextDigest(), key, input.externalTransferConfirmed())
                : service.submit(http, input.tenantId(), input.workspaceId(), actionId, input.text(), input.requirements(), input.options(), key, input.externalTransferConfirmed());
        return ResponseEntity.accepted().location(URI.create("/api/ai-new/actions/executions/" + result.action().actionExecutionId())).body(result);
    }

    @PostMapping("/{actionId}/context")
    public Object preview(HttpServletRequest http, @PathVariable String actionId, @RequestBody Submission input) {
        return service.preview(http, input.tenantId(), input.workspaceId(), actionId, input.text(), input.requirements(),
                input.target(), input.references(), input.options());
    }

    @ExceptionHandler(ContextCapacityException.class)
    public ResponseEntity<?> capacity(ContextCapacityException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", failure.error(), "capacity", failure.capacity()));
    }

    @GetMapping("/executions/{id}")
    public Object find(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.find(http, tenantId, workspaceId, id);
    }

    @PostMapping("/executions/{id}/regenerate")
    public Object regenerate(HttpServletRequest http, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody Regeneration input) {
        var result = service.regenerate(http, input.tenantId(), input.workspaceId(), id, input.options(), key, input.externalTransferConfirmed());
        return ResponseEntity.accepted().location(URI.create("/api/ai-new/actions/executions/" + result.action().actionExecutionId())).body(result);
    }

    @PostMapping("/executions/{id}/cancel")
    public Object cancel(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return Map.of("status", service.cancel(http, tenantId, workspaceId, id));
    }

    @GetMapping(value = "/executions/{id}/events", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> events(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId,
                                             @RequestParam String workspaceId, @RequestParam(defaultValue = "-1") long after,
                                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (lastEventId != null) after = Long.parseLong(lastEventId);
        var observation = service.observe(http, tenantId, workspaceId, id);
        return ResponseEntity.ok().header("Cache-Control", "no-store, no-transform").header("X-Accel-Buffering", "no")
                .body(streams.open(observation, after));
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ExecutionError> failure(BaseException failure) {
        return NewAiErrorResponses.failure(failure);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ExecutionError> denied() {
        return NewAiErrorResponses.denied();
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ExecutionError> invalid(Exception failure) {
        return NewAiErrorResponses.invalid();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ExecutionError> unavailable() {
        return NewAiErrorResponses.unavailable();
    }
}
