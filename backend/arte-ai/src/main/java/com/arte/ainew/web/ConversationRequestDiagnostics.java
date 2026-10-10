package com.arte.ainew.web;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.core.pojo.PageParam;
import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Mono;

/**
 * MVC 异步派发仍可读取的诊断信息；只保存标识、分页和版本，不保存正文、认证头或幂等键。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 22:28 ✾
 */
public record ConversationRequestDiagnostics(
        String operation, String tenantId, String workspaceId, String conversationId,
        Long current, Long size, Long expectedVersion, String traceId, String executionId, String subjectId) {
    public static final String ATTRIBUTE = ConversationRequestDiagnostics.class.getName();

    public static Mono<ExecutionContext> capture(HttpServletRequest http, String operation,
                                                 ConversationRequests.Scope scope, String conversationId,
                                                 PageParam page, Long expectedVersion,
                                                 java.util.function.Supplier<Mono<ExecutionContext>> source) {
        var pending = new ConversationRequestDiagnostics(operation, field(scope.tenantId()), field(scope.workspaceId()),
                field(conversationId), page == null ? null : page.getCurrent(), page == null ? null : page.getSize(),
                expectedVersion, null, null, null);
        http.setAttribute(ATTRIBUTE, pending);
        // 必须在 MVC 线程构造 source，以保留现有 Authentication 捕获时机。
        return source.get().doOnNext(context -> http.setAttribute(ATTRIBUTE, new ConversationRequestDiagnostics(
                operation, pending.tenantId(), pending.workspaceId(), pending.conversationId(), pending.current(),
                pending.size(), expectedVersion, context.traceId(), context.executionId(),
                field(context.authorization().principal().subjectId()))));
    }

    private static String field(String value) {
        return value == null ? null : value.substring(0, Math.min(256, value.length()))
                .replaceAll("[\\p{Cntrl}]", "_");
    }
}
