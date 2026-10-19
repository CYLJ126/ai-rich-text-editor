package com.arte.ainew.tool.management;

import com.arte.ainew.web.ToolManagementExceptionHandler;
import com.arte.core.i18n.MessageUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * 缺失请求头给出可修正的提示；日志只保留声明名称和固定原因，不泄露请求值或正文。
 */
public class ToolManagementExceptionHandlerTest {
    private final ToolManagementExceptionHandler handler = new ToolManagementExceptionHandler();

    @Before
    public void messages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        MessageUtils.setMessageSource(source);
    }

    @After
    public void cleanup() {
        MessageUtils.setMessageSource(null);
    }

    @Test
    public void missingHeaderLogsItsNameAndReasonWithoutRequestValuesOrStackTrace() throws Exception {
        var events = new CopyOnWriteArrayList<LogEvent>();
        var appender = new AbstractAppender("tool-missing-header-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        var logger = (Logger) LogManager.getLogger(ToolManagementExceptionHandler.class);
        var previous = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            var request = new MockHttpServletRequest("POST", "/arte/ai-new/tool/refreshLocalCatalog");
            request.addHeader("Authorization", "Bearer private-header-token");
            request.setContent("{\"reason\":\"private-review-reason\"}".getBytes(StandardCharsets.UTF_8));
            var result = handler.handle(missing(false), request, Locale.SIMPLIFIED_CHINESE);
            assertEquals(400, result.getStatusCode().value());
            assertNotNull(result.getBody());
            assertEquals("205055", result.getBody().getCode());
            assertEquals("缺少必填请求头：Idempotency-Key。请在请求头中补充后重试。", result.getBody().getDesc());
            assertFalse(result.getBody().getSuccess());
            assertNull(result.getBody().getData());
            assertEquals(1, events.size());
            String log = events.getFirst().getMessage().getFormattedMessage();
            assertTrue(log.contains("path=/arte/ai-new/tool/refreshLocalCatalog"));
            assertTrue(log.contains("reason=MISSING_REQUEST_HEADER header=Idempotency-Key"));
            assertFalse(log.contains("private-header-token"));
            assertFalse(log.contains("private-review-reason"));
            assertNull(events.getFirst().getThrown());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    public void fieldDiagnosticsAreBoundedAndNeverUseRejectedValuesOrValidationMessages() throws Exception {
        var events = new CopyOnWriteArrayList<LogEvent>();
        var appender = new AbstractAppender("tool-invalid-fields-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        var logger = (Logger) LogManager.getLogger(ToolManagementExceptionHandler.class);
        var previous = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            var binding = new BeanPropertyBindingResult(Map.of(), "request");
            binding.addError(new FieldError("request", "settings.secrets[private-map-key]", "private-value", false,
                    new String[]{"NotNull"}, null, "private-validation-message"));
            binding.addError(new FieldError("request", "reason\nforged-log", "private-value", false,
                    new String[]{"NotNull"}, null, "private-validation-message"));
            binding.addError(new FieldError("request", "expectedVersion", "private-value", false,
                    new String[]{"Pattern"}, null, "private-validation-message"));
            for (int i = 0; i < 30; i++)
                binding.addError(new FieldError("request", "field" + i, "private-value", false,
                        new String[]{"Size"}, null, "private-validation-message"));
            var parameter = new MethodParameter(ToolManagementExceptionHandlerTest.class.getDeclaredMethod("endpoint", String.class), 0);
            var request = new MockHttpServletRequest("POST", "/ai-new/tool/publishTool");
            var result = handler.handle(new MethodArgumentNotValidException(parameter, binding), request, Locale.US);
            assertEquals(400, result.getStatusCode().value());
            assertNotNull(result.getBody());
            String response = result.getBody().getDesc();
            assertTrue(response.contains("expectedVersion (invalid format)"));
            assertTrue(response.contains("$request (required)"));
            assertEquals(1, events.size());
            String log = events.getFirst().getMessage().getFormattedMessage();
            assertTrue(log.contains("reason=INVALID_REQUEST_FIELDS"));
            assertTrue(log.contains("expectedVersion:FORMAT"));
            assertEquals(16, log.substring(log.indexOf("fields=[") + 8, log.length() - 1).split(", ").length);
            for (String output : new String[]{response, log}) {
                assertFalse(output.contains("private-value"));
                assertFalse(output.contains("private-validation-message"));
                assertFalse(output.contains("private-map-key"));
                assertFalse(output.contains("forged-log"));
                assertFalse(output.contains("\n"));
            }
            assertNull(events.getFirst().getThrown());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    public void headerPresentButNullAfterConversionIsReportedAsInvalid() throws Exception {
        var request = new MockHttpServletRequest("POST", "/ai-new/tool/refreshLocalCatalog");
        request.addHeader("Idempotency-Key", "private-invalid-value");
        var result = handler.handle(missing(true), request, Locale.US);
        assertEquals(400, result.getStatusCode().value());
        assertNotNull(result.getBody());
        assertEquals("205055", result.getBody().getCode());
        assertEquals("Invalid required request header: Idempotency-Key. Check its format and retry.", result.getBody().getDesc());
        assertFalse(result.getBody().getDesc().contains("private-invalid-value"));
    }

    private static MissingRequestHeaderException missing(boolean afterConversion) throws Exception {
        var parameter = new MethodParameter(ToolManagementExceptionHandlerTest.class.getDeclaredMethod("endpoint", String.class), 0);
        return new MissingRequestHeaderException("Idempotency-Key", parameter, afterConversion);
    }

    private static void endpoint(String key) {
    }
}
