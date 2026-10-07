package com.arte.core.utils;

import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.BusinessException;
import com.arte.core.exception.CommonException;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExceptionUtilTest {

    private static final String INTERNAL_DETAIL =
            "jdbc:mysql://internal/db?password=TEST_SECRET; SELECT * FROM private_table; /srv/private/config";

    @BeforeEach
    void initializeMessages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        MessageUtils.setMessageSource(source);
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void clearMessages() {
        MessageUtils.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void systemErrorsNeverExposeRawMessagesThroughEitherResponseWrapper() {
        for (Throwable error : List.of(
                new SQLException(INTERNAL_DETAIL),
                new IllegalArgumentException(INTERNAL_DETAIL),
                new RuntimeException(INTERNAL_DETAIL),
                new RuntimeException(INTERNAL_DETAIL, new SQLException(INTERNAL_DETAIL)))) {
            assertSafeResponses(error, ResultCodeEnum.SYSTEM_EXCEPTION);
        }
    }

    @Test
    void registeredBusinessCodeUsesWhitelistedTextInsteadOfCustomMessageOrCause() {
        var error = new BusinessException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET,
                INTERNAL_DETAIL, new SQLException(INTERNAL_DETAIL));

        assertSafeResponses(error, ResultCodeEnum.AI_INSUFFICIENT_BUDGET);
        assertEquals("可用预算不足", ExceptionUtil.desensitize(error));
        // 响应转换不修改原异常，服务端仍能记录完整错误及堆栈。
        assertEquals(INTERNAL_DETAIL, error.getMessage());
        assertEquals(INTERNAL_DETAIL, error.getCause().getMessage());
    }

    @Test
    void commonAndBusinessExceptionsWrappingThrowableCannotDiscloseItsText() {
        var cause = new SQLException(INTERNAL_DETAIL);
        for (Throwable error : List.of(new CommonException(cause), new BusinessException(cause),
                new CommonException(INTERNAL_DETAIL), new BusinessException(INTERNAL_DETAIL))) {
            assertSafeResponses(error, ResultCodeEnum.EXCEPTION);
        }
    }

    @Test
    void directCauseRetainsBusinessCodeAndNeverUsesOuterMessage() {
        var business = new BusinessException(ResultCodeEnum.AI_VERSION_CONFLICT, INTERNAL_DETAIL);
        assertSafeResponses(new RuntimeException(INTERNAL_DETAIL, business), ResultCodeEnum.AI_VERSION_CONFLICT);
    }

    @Test
    void missingExceptionOrResultCodeUsesSystemFallback() {
        assertSafeResponses(null, ResultCodeEnum.SYSTEM_EXCEPTION);
        assertSafeResponses(new CommonException(), ResultCodeEnum.SYSTEM_EXCEPTION);
    }

    @Test
    void safeDescriptionUsesResponseThreadsLocale() {
        var error = new CommonException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET, INTERNAL_DETAIL);
        assertEquals("可用预算不足", ExceptionUtil.desensitize(error));

        LocaleContextHolder.setLocale(Locale.US);
        assertSafeResponses(error, ResultCodeEnum.AI_INSUFFICIENT_BUDGET);
        assertEquals("The available budget is insufficient", ExceptionUtil.desensitize(error));
    }

    private static void assertSafeResponses(Throwable error, ResultCodeEnum expectedCode) {
        var pair = ExceptionUtil.desensitizePair(error);
        assertEquals(expectedCode, pair.getKey());
        assertEquals(expectedCode.getDesc(), pair.getValue());
        assertFalse(pair.getValue().contains("TEST_SECRET"));
        assertFalse(pair.getValue().contains("private_table"));
        assertFalse(pair.getValue().contains("/srv/private/config"));
        assertEquals(pair.getValue(), ExceptionUtil.desensitize(error));

        var result = ResultContext.exception(error);
        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals(expectedCode.getCode(), result.getCode());
        assertEquals(pair.getValue(), result.getDesc());

        var page = PageView.exception(error);
        assertEquals(Boolean.FALSE, page.getSuccess());
        assertEquals(expectedCode.getCode(), page.getCode());
        assertEquals(pair.getValue(), page.getDesc());
    }
}
