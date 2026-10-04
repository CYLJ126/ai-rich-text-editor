package com.arte.app.ainew;

import com.arte.ai.model.context.ResourceRetrievalRequest;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.ExecutionError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * 独立新会话路径；身份、模型绑定及上下文均由服务端确定。
 */
@RestController
@RequestMapping("/api/ai-new/conversations")
@ConditionalOnProperty(name = "arte.ai-new.chat.enabled", havingValue = "true")
@PreAuthorize("isAuthenticated()")
public class NewChatController {
    public record Creation(String tenantId, String workspaceId, String title) {
    }

    public record Naming(String tenantId, String workspaceId, long expectedVersion, String title) {
    }

    public record Submission(String tenantId, String workspaceId, long expectedVersion, String text,
                             ModelOptions options, boolean externalTransferConfirmed, String previewId, String expectedContextDigest) {
        public Submission(String tenantId, String workspaceId, long expectedVersion, String text, ModelOptions options, boolean externalTransferConfirmed) {
            this(tenantId, workspaceId, expectedVersion, text, options, externalTransferConfirmed, null, null);
        }
    }

    public record RetrievalPreview(String tenantId, String workspaceId, long expectedVersion, String text,
                                   ResourceRetrievalRequest retrieval, ModelOptions options) { }

    @GetMapping("/rag/articles")
    public Object articles(HttpServletRequest http, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.articles(http, tenantId, workspaceId);
    }

    @PostMapping("/{id}/rag-preview")
    public Object preview(HttpServletRequest http, @PathVariable String id, @RequestBody RetrievalPreview input) {
        return service.preview(http, input.tenantId(), input.workspaceId(), id, input.expectedVersion(), input.text(), input.retrieval(), input.options());
    }

    @GetMapping("/{id}/rag-preview/{previewId}")
    public Object preview(HttpServletRequest http, @PathVariable String id, @PathVariable String previewId,
                          @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.preview(http, tenantId, workspaceId, id, previewId);
    }

    @GetMapping("/{id}/turns/{turnId}/context")
    public Object context(HttpServletRequest http, @PathVariable String id, @PathVariable String turnId,
                          @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.context(http, tenantId, workspaceId, id, turnId);
    }

    public record Regeneration(String tenantId, String workspaceId, long expectedVersion, String originalTurnId,
                               ModelOptions options, boolean externalTransferConfirmed) {
    }

    private final NewChatCallService service;
    private final ChatEventStreams streams;

    public NewChatController(NewChatCallService service) {
        this(service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NewChatController(NewChatCallService service, ChatEventStreams streams) {
        this.service = service;
        this.streams = streams;
    }

    @GetMapping(value = "/{id}/turns/{turnId}/events", produces = "text/event-stream")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> events(HttpServletRequest http,
                                                                                                   @PathVariable String id, @PathVariable String turnId, @RequestParam String tenantId, @RequestParam String workspaceId,
                                                                                                   @RequestParam(defaultValue = "-1") long after, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (lastEventId != null) after = Long.parseLong(lastEventId);
        var observation = service.observe(http, tenantId, workspaceId, id, turnId);
        if (streams == null) throw new IllegalArgumentException("streaming observer unavailable");
        return ResponseEntity.ok().header("Cache-Control", "no-store, no-transform").header("X-Accel-Buffering", "no")
                .body(streams.open(observation, after));
    }

    @PostMapping
    public Object create(HttpServletRequest http, @RequestBody Creation input) {
        var result = service.create(http, input.tenantId(), input.workspaceId(), input.title());
        return ResponseEntity.created(URI.create("/api/ai-new/conversations/" + result.conversationId())).body(result);
    }

    @GetMapping
    public Object list(HttpServletRequest http, @RequestParam String tenantId, @RequestParam String workspaceId,
                       @RequestParam(required = false) String title, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return service.list(http, tenantId, workspaceId, title, offset, limit);
    }

    @GetMapping("/{id}")
    public Object find(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.find(http, tenantId, workspaceId, id);
    }

    @PatchMapping("/{id}")
    public Object rename(HttpServletRequest http, @PathVariable String id, @RequestBody Naming input) {
        return service.rename(http, input.tenantId(), input.workspaceId(), id, input.expectedVersion(), input.title());
    }

    @DeleteMapping("/{id}")
    public Object delete(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId, @RequestParam long expectedVersion) {
        return service.delete(http, tenantId, workspaceId, id, expectedVersion);
    }

    /**
     * 提交用户输入。
     *
     * @param http  HTTP 请求
     * @param id    会话 ID
     * @param key   密钥
     * @param input 提交输入
     * @return 本轮结果
     */
    @PostMapping("/{id}/turns")
    public Object submit(HttpServletRequest http, @PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody Submission input) {
        return ResponseEntity.accepted().body(service.submit(http, input.tenantId(), input.workspaceId(), id, input.expectedVersion(), input.text(), input.options(), key, input.externalTransferConfirmed(), input.previewId(), input.expectedContextDigest()));
    }

    @PostMapping("/{id}/regenerate")
    public Object regenerate(HttpServletRequest http, @PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody Regeneration input) {
        return ResponseEntity.accepted().body(service.regenerate(http, input.tenantId(), input.workspaceId(), id, input.expectedVersion(), input.originalTurnId(), input.options(), key, input.externalTransferConfirmed()));
    }

    @GetMapping("/{id}/turns")
    public Object history(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId,
                          @RequestParam(defaultValue = "9223372036854775807") long beforeSequence, @RequestParam(defaultValue = "50") int limit) {
        return service.history(http, tenantId, workspaceId, id, beforeSequence, limit);
    }

    @GetMapping("/{id}/turns/{turnId}")
    public Object turn(HttpServletRequest http, @PathVariable String id, @PathVariable String turnId, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return service.turn(http, tenantId, workspaceId, id, turnId);
    }

    @PostMapping("/{id}/turns/{turnId}/cancel")
    public Object cancel(HttpServletRequest http, @PathVariable String id, @PathVariable String turnId, @RequestParam String tenantId, @RequestParam String workspaceId) {
        return Map.of("status", service.cancel(http, tenantId, workspaceId, id, turnId));
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ExecutionError> failure(BaseException error) {
        return NewAiErrorResponses.failure(error);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ExecutionError> denied(org.springframework.security.access.AccessDeniedException error) {
        return NewAiErrorResponses.denied(error);
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ExecutionError> invalid(Exception error) {
        return NewAiErrorResponses.invalid();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ExecutionError> unavailable() {
        return NewAiErrorResponses.unavailable();
    }
}
