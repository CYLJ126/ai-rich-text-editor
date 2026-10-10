package com.arte.app.web.platform;

import com.arte.core.constant.CoreConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 服务端请求追踪标识，保存到请求属性以跨越 MVC 异步分发。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:42 ✾
 */
public final class PlatformHttpContext {
    private static final String TRACE_ATTRIBUTE = PlatformHttpContext.class.getName() + ".traceId";

    private PlatformHttpContext() {
    }

    public static String traceId(HttpServletRequest request) {
        if (request.getAttribute(TRACE_ATTRIBUTE) instanceof String trace) return trace;
        String trace = MDC.get(CoreConstant.LOG_TRACE_ID);
        if (trace == null || trace.isBlank() || trace.length() > 256 || !trace.equals(trace.strip())
                || trace.chars().anyMatch(Character::isISOControl)) {
            trace = UUID.randomUUID().toString();
        }
        request.setAttribute(TRACE_ATTRIBUTE, trace);
        return trace;
    }
}
