package com.arte.ainew.admission;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.CommonException;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.pojo.ResultContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/** 统一准入结果码、存储拒绝转换及跨线程展示语言的集成契约。 */
public class AdmissionExceptionTest {
    @Before public void initializeMessages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        MessageUtils.setMessageSource(source);
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @After public void clearMessages() {
        MessageUtils.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test public void admissionResultCodeIsPreservedInCommonResponse() {
        var error = new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
        assertTrue(error instanceof CommonException);
        assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, error.getResultCode());
        var response = ResultContext.exception(error);
        assertEquals("205027", response.getCode());
        assertEquals(Boolean.FALSE, response.getSuccess());
        assertTrue(response.getDesc().contains("幂等键已用于不同的请求内容"));

        var budget = ResultContext.exception(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED));
        assertEquals("205024", budget.getCode());
        assertNotEquals(response.getCode(), budget.getCode());
    }

    @Test public void typedConstructorAndWrappedExceptionRetainAiIdentity() {
        var error = new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
        assertEquals(ResultCodeEnum.AI_VERSION_CONFLICT, error.getResultCode());
        var response = ResultContext.exception(new RuntimeException("Execution boundary failure", error));
        assertEquals(error.getResultCode().getCode(), response.getCode());
        assertEquals(ResultCodeEnum.CHAT_EXCEPTION.getCode(), ResultContext.exception(new CommonException(ResultCodeEnum.CHAT_EXCEPTION)).getCode());
    }

    @Test public void everyRejectedStoreOutcomeHasADedicatedResultCode() {
        var responseCodes = Map.ofEntries(
                Map.entry(StoreOutcome.Code.NOT_FOUND, "205028"),
                Map.entry(StoreOutcome.Code.OWNER_MISMATCH, "205029"),
                Map.entry(StoreOutcome.Code.VERSION_CONFLICT, "205030"),
                Map.entry(StoreOutcome.Code.IDEMPOTENCY_CONFLICT, "205027"),
                Map.entry(StoreOutcome.Code.LEASE_LOST, "205031"),
                Map.entry(StoreOutcome.Code.INVALID_STATE, "205032"),
                Map.entry(StoreOutcome.Code.RECONCILIATION_REQUIRED, "205033"),
                Map.entry(StoreOutcome.Code.CONVERSATION_BUSY, "205034"),
                Map.entry(StoreOutcome.Code.INSUFFICIENT_BUDGET, "205035"),
                Map.entry(StoreOutcome.Code.CURRENCY_MISMATCH, "205036"),
                Map.entry(StoreOutcome.Code.RATE_MISMATCH, "205026"),
                Map.entry(StoreOutcome.Code.CURSOR_EXPIRED, "205037"));
        for (var outcome : StoreOutcome.Code.values()) {
            if (outcome == StoreOutcome.Code.APPLIED || outcome == StoreOutcome.Code.REPLAYED) { continue; }
            var error = AdmissionException.fromStoreRejection(outcome);
            assertNotEquals(outcome.name(), ResultCodeEnum.AI_ADMISSION_EXCEPTION, error.getResultCode());
            assertEquals("AI_" + outcome.name(), error.getResultCode().name());
            assertEquals(responseCodes.get(outcome), ResultContext.exception(error).getCode());
        }
    }

    @Test public void genericAdmissionResultCodeUsesSafeLocalizedMessage() {
        var error = new AdmissionException(ResultCodeEnum.AI_ADMISSION_EXCEPTION);
        assertEquals(ResultCodeEnum.AI_ADMISSION_EXCEPTION, error.getResultCode());
        var response = ResultContext.exception(error);
        assertEquals("305001", response.getCode());
        assertTrue(response.getDesc().contains("AI 请求受理异常"));
        assertFalse(response.getDesc().contains(error.getResultCode().name()));
    }

    @Test public void responseUsesReadingThreadsLocaleWithoutChangingResultCode() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        AdmissionException error;
        try {
            error = executor.submit(() -> {
                LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
                try { return new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED); }
                finally { LocaleContextHolder.resetLocaleContext(); }
            }).get();
        } finally { executor.shutdownNow(); }

        assertEquals("执行期限已过", error.getMessage());
        LocaleContextHolder.setLocale(Locale.US);
        assertEquals("The execution deadline has expired", error.getMessage());
        assertTrue(ResultContext.exception(error).getDesc().contains("The execution deadline has expired"));
        LocaleContextHolder.setLocale(Locale.TRADITIONAL_CHINESE);
        assertEquals("執行期限已過", error.getMessage());
        assertEquals(ResultCodeEnum.AI_DEADLINE_EXCEEDED, error.getResultCode());
        assertEquals("205005", error.getResultCode().getCode());
    }

    @Test public void resultCodesAreUniqueAndAllAiMessagesAreAvailableInEachLanguage() {
        var values = ResultCodeEnum.values();
        assertEquals(values.length, new HashSet<>(Arrays.stream(values).map(ResultCodeEnum::getCode).toList()).size());
        for (var locale : new Locale[]{ Locale.ROOT, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.US }) {
            LocaleContextHolder.setLocale(locale);
            for (var value : values) {
                if (!value.name().startsWith("AI_")) { continue; }
                assertFalse(value.name(), value.getDesc().startsWith("result."));
                var error = new AdmissionException(value);
                assertEquals(value, error.getResultCode());
                assertFalse(value.name(), ResultContext.exception(error).getDesc().contains("result.ai.admission."));
            }
        }
    }

    @Test public void successOutcomesAndOtherModulesCannotMasqueradeAsAdmissionErrors() {
        assertThrows(IllegalArgumentException.class, () -> AdmissionException.fromStoreRejection(StoreOutcome.Code.APPLIED));
        assertThrows(IllegalArgumentException.class, () -> AdmissionException.fromStoreRejection(StoreOutcome.Code.REPLAYED));
        assertThrows(NullPointerException.class, () -> AdmissionException.fromStoreRejection(null));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionException(ResultCodeEnum.SUCCESS));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionException(ResultCodeEnum.CHAT_EXCEPTION));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionException((ResultCodeEnum) null));
    }
}
