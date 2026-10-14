package com.arte.app.diagnostics;

import com.arte.core.diagnostics.RequestObservation;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * Servlet 入口开始，ASYNC 完成结束；工厂初次授权、响应序列化及 SSE 租期均包含在计时内。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 09:41 ✾
 */
@Slf4j
public final class NewAiHttpObservationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = NewAiHttpObservationFilter.class.getName();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/ai-new/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var observation = (RequestObservation) request.getAttribute(ATTRIBUTE);
        if (observation == null) {
            observation = new RequestObservation(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()), request.getHeader("X-Arte-Measurement-Id"));
            request.setAttribute(ATTRIBUTE, observation);
        }
        final var metric = observation;
        response.setHeader("X-Arte-Trace-Id", metric.traceId);
        var existing = org.springframework.web.util.WebUtils.getNativeResponse(response, ObservedResponse.class);
        // ASYNC 分发可能在外面套框架包装器；不能再套一层而把同一字节计数两次。
        var wrapped = existing != null && existing.metric == metric ? response : new ObservedResponse(response, metric);
        var listener = new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                finish(metric, wrapped.getStatus());
            }

            @Override
            public void onTimeout(AsyncEvent event) { /* onComplete 是包括错误响应后的终点 */ }

            @Override
            public void onError(AsyncEvent event) { /* 同上，不在错误派发前提前结算 */ }

            @Override
            public void onStartAsync(AsyncEvent event) {
                event.getAsyncContext().addListener(this);
            }
        };
        var input = new HttpServletRequestWrapper(request) {
            @Override
            public AsyncContext startAsync() {
                var context = super.startAsync(this, wrapped);
                context.addListener(listener);
                return context;
            }

            @Override
            public AsyncContext startAsync(ServletRequest input, ServletResponse output) {
                var context = super.startAsync(input, output);
                context.addListener(listener);
                return context;
            }
        };
        try (var ignored = RequestObservation.bind(metric)) {
            try {
                chain.doFilter(input, wrapped);
            } finally {
                if (!request.isAsyncStarted()) finish(metric, wrapped.getStatus());
            }
        }
    }

    private void finish(RequestObservation metric, int status) {
        var values = metric.finish(status);
        if (values != null) log.info("AI_HTTP_METRICS {}", JSON.writeValueAsString(values));
    }

    private static final class ObservedResponse extends HttpServletResponseWrapper {
        private final RequestObservation metric;
        private ServletOutputStream stream;
        private PrintWriter writer;

        ObservedResponse(HttpServletResponse response, RequestObservation metric) {
            super(response);
            this.metric = metric;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                var output = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return output.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        output.setWriteListener(listener);
                    }

                    @Override
                    public void write(int value) throws IOException {
                        output.write(value);
                        metric.wrote(1);
                    }

                    @Override
                    public void write(byte[] value, int offset, int length) throws IOException {
                        output.write(value, offset, length);
                        metric.wrote(length);
                    }

                    @Override
                    public void flush() throws IOException {
                        output.flush();
                        metric.committed();
                    }

                    @Override
                    public void close() throws IOException {
                        output.close();
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null)
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) writer.flush();
            else if (stream != null) stream.flush();
            super.flushBuffer();
            metric.committed();
        }

        @Override
        public void sendError(int status) throws IOException {
            super.sendError(status);
            metric.committed();
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            super.sendError(status, message);
            metric.committed();
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            super.sendRedirect(location);
            metric.committed();
        }
    }
}
