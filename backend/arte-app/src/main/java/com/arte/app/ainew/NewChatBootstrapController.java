package com.arte.app.ainew;

import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.ExecutionError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天关闭时也可查询开关状态；有效配置只向通过现有身份校验的成员返回。
 */
@RestController
@RequestMapping("/api/ai-new/chat")
@PreAuthorize("isAuthenticated()")
public class NewChatBootstrapController {
    private final ObjectProvider<NewChatBootstrapService> services;

    public NewChatBootstrapController(ObjectProvider<NewChatBootstrapService> services) {
        this.services = services;
    }

    @GetMapping("/bootstrap")
    public NewChatBootstrap bootstrap(HttpServletRequest request) {
        var service = services.getIfAvailable();
        return service == null ? NewChatBootstrap.disabled() : service.read(request);
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ExecutionError> failure(BaseException error) {
        return NewAiErrorResponses.failure(error);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ExecutionError> denied() {
        return NewAiErrorResponses.denied();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ExecutionError> unavailable() {
        return NewAiErrorResponses.unavailable();
    }
}
