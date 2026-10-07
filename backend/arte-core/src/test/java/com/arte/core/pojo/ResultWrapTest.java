package com.arte.core.pojo;

import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.BusinessException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ResultWrapTest {

    private final List<LogEvent> events = new ArrayList<>();
    private final Logger resultLogger = (Logger) LogManager.getLogger(ResultContext.class);
    private final Logger pageLogger = (Logger) LogManager.getLogger(PageView.class);
    private final AbstractAppender appender = new AbstractAppender(
            "ResultWrapTest", null, null, false, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    };

    @BeforeEach
    void captureLogs() {
        appender.start();
        resultLogger.addAppender(appender);
        pageLogger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingLogs() {
        resultLogger.removeAppender(appender);
        pageLogger.removeAppender(appender);
        appender.stop();
    }

    @Test
    void resultWrappersDoNotReadOrLogRequestContentsAndRetainOriginalException() {
        var request = new SensitiveRequest();
        var error = new BusinessException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET);

        var single = ResultContext.wrap(request, (Function<SensitiveRequest, String>) value -> {
            throw error;
        });
        var multiple = ResultContext.wrap(request, request, (first, second) -> {
            throw error;
        });

        assertFailure(single, error);
        assertFailure(multiple, error);
        assertSafeLogs(request, error);
    }

    @Test
    void pageWrappersDoNotReadOrLogRequestContentsAndRetainOriginalException() {
        var request = new SensitiveRequest();
        var error = new BusinessException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET);

        var single = PageView.wrap(request, value -> {
            throw error;
        });
        var multiple = PageView.wrap(request, request, (first, second) -> {
            throw error;
        });

        assertFailure(single, error);
        assertFailure(multiple, error);
        assertSafeLogs(request, error);
    }

    @Test
    void resultWrappersReturnOriginalBusinessFailureWhenRequestsAreNull() {
        var error = new BusinessException(ResultCodeEnum.AI_VERSION_CONFLICT);
        var single = assertDoesNotThrow(() -> ResultContext.wrap(null,
                (Function<Object, String>) value -> { throw error; }));
        assertFailure(single, error);
        for (Object[] requests : new Object[][]{{null, "value"}, {"value", null}, {null, null}}) {
            var result = assertDoesNotThrow(() -> ResultContext.wrap(requests[0], requests[1],
                    (first, second) -> { throw error; }));
            assertFailure(result, error);
        }
    }

    @Test
    void pageWrappersReturnOriginalBusinessFailureWhenRequestsAreNull() {
        var error = new BusinessException(ResultCodeEnum.AI_VERSION_CONFLICT);
        var single = assertDoesNotThrow(() -> PageView.wrap(null, value -> { throw error; }));
        assertFailure(single, error);
        for (Object[] requests : new Object[][]{{null, "value"}, {"value", null}, {null, null}}) {
            var result = assertDoesNotThrow(() -> PageView.wrap(requests[0], requests[1],
                    (first, second) -> { throw error; }));
            assertFailure(result, error);
        }
    }

    private static void assertFailure(IResult result, BusinessException error) {
        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals(error.getResultCode().getCode(), result.getCode());
    }

    private void assertSafeLogs(SensitiveRequest request, Throwable error) {
        assertEquals(0, request.toStringCalls);
        assertEquals(2, events.size());
        for (LogEvent event : events) {
            assertFalse(event.getMessage().getFormattedMessage().contains("TEST_PASSWORD"));
            assertSame(error, event.getThrown());
        }
    }

    private static class SensitiveRequest {
        private int toStringCalls;

        @Override
        public String toString() {
            toStringCalls++;
            return "password=TEST_PASSWORD";
        }
    }
}
