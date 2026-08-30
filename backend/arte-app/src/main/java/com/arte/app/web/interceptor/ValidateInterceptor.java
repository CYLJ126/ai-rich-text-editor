package com.arte.app.web.interceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 通用拦截器，做调用信息打印，及基本校验
 *
 * @author zhangsc
 * @since 2024/7/11 13:50
 */
@Service
@Slf4j
public class ValidateInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            logErrorDispatch(request);
        }
        return true;
    }

    private void logErrorDispatch(HttpServletRequest request) {
        Object originalException = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        String message = "错误分发：method=%s, requestUri=%s, originalRequestUri=%s, originalQueryString=%s, " +
                "errorStatus=%s, errorMessage=%s, servletName=%s";
        Object[] arguments = {
                request.getMethod(),
                request.getRequestURI(),
                request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                request.getAttribute(RequestDispatcher.ERROR_QUERY_STRING),
                request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE),
                request.getAttribute(RequestDispatcher.ERROR_MESSAGE),
                request.getAttribute(RequestDispatcher.ERROR_SERVLET_NAME)
        };

        String formatted = message.formatted(arguments);
        if (originalException instanceof Throwable throwable) {
            log.error(formatted, throwable);
        } else {
            log.error(formatted);
        }
    }
}
