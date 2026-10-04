package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.core.constant.CoreConstant;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 阻塞组合适配器的短暂兼容作用域，必须使用 try-with-resources。
 * 仅映射已验证的用户 ID／名称和 traceId，不复制 Token、密码、角色或旧授权快照。
 * 旧服务仍须执行自身资源授权；作用域不能跨线程、跨异步 Publisher 或跨事务线程使用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public final class LegacyUserContextScope implements AutoCloseable {
    private static final ThreadLocal<Deque<LegacyUserContextScope>> SCOPES = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final UserOnlineInfo previousUser;
    private final String previousTrace;
    private boolean closed;

    private LegacyUserContextScope(ExecutionContext context) {
        var principal = context.authorization().principal();
        if (principal.kind() != ExecutionPrincipal.Kind.USER) {
            throw new IllegalArgumentException("Service identity cannot impersonate a legacy user");
        }
        int userId;
        try {
            userId = Integer.parseInt(principal.subjectId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Legacy user requires an integer subject ID", e);
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("Legacy user ID must be positive");
        }
        previousUser = UserContext.hasUserOnlineInfo() ? UserContext.getUserOnlineInfo() : null;
        previousTrace = MDC.get(CoreConstant.LOG_TRACE_ID);
        MDC.put(CoreConstant.LOG_TRACE_ID, context.traceId());
        UserContext.setUserOnlineInfo(new UserOnlineInfo().setId(userId).setUserName(principal.subjectName()));
        Deque<LegacyUserContextScope> scopes = SCOPES.get();
        if (scopes == null) {
            scopes = new ArrayDeque<>();
            SCOPES.set(scopes);
        }
        scopes.push(this);
    }

    public static LegacyUserContextScope open(ExecutionContext context) {
        return new LegacyUserContextScope(Objects.requireNonNull(context, "context"));
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Legacy context scope must close on its owner thread");
        }
        if (closed) {
            return;
        }
        Deque<LegacyUserContextScope> scopes = SCOPES.get();
        if (scopes == null || scopes.peek() != this) {
            throw new IllegalStateException("Legacy context scopes must close in reverse order");
        }
        try {
            if (previousUser == null) {
                UserContext.clear();
            } else {
                UserContext.setUserOnlineInfo(previousUser);
            }
            if (previousTrace == null) {
                MDC.remove(CoreConstant.LOG_TRACE_ID);
            } else {
                MDC.put(CoreConstant.LOG_TRACE_ID, previousTrace);
            }
        } finally {
            scopes.pop();
            if (scopes.isEmpty()) {
                SCOPES.remove();
            }
            closed = true;
        }
    }
}
