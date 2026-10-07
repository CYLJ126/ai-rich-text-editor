package com.arte.core.i18n;

import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import com.arte.core.serialize.SerializerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ResponseLocaleTest {

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
    void explicitMessageLookupPreservesThreadLocaleAndSupportsArguments() {
        assertEquals("Success", MessageUtils.get(Locale.US, "result.success"));
        assertEquals("Failure", ResultCodeEnum.FAIL.getDesc(Locale.US));
        assertEquals("Conversation not found: 42",
                MessageUtils.get(Locale.US, "error.ai.conversationNotFound", 42));
        assertEquals("成功", MessageUtils.get((Locale) null, "result.success"));
        assertEquals("unregistered text", MessageUtils.get(Locale.US, "unregistered text"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleContextHolder.getLocale());
    }

    @Test
    void settersAndJsonReadingStoreDescriptionsWithoutTranslatingThem() {
        var result = ResultContext.success("data").setDesc("result.fail");
        var page = PageView.success(List.of("record")).setDesc("result.fail");
        assertEquals("result.fail", result.getDesc());
        assertEquals("result.fail", page.getDesc());

        var mapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();
        assertEquals("result.fail", mapper.readValue(mapper.writeValueAsString(result), ResultContext.class).getDesc());
        assertEquals("result.fail", mapper.readValue(mapper.writeValueAsString(page), PageView.class).getDesc());
        assertEquals("result.fail", page.copy().getDesc());
    }

    @Test
    void existingFactoriesStillTranslateKeysForSynchronousMvcCalls() {
        assertEquals("成功", ResultContext.success("data", "result.success").getDesc());
        assertEquals("失败", ResultContext.fail("result.fail").getDesc());
        assertEquals("失败", PageView.fail("result.fail").getDesc());

        LocaleContextHolder.setLocale(Locale.US);
        assertEquals("Success", ResultContext.success("data").getDesc());
        assertEquals("Failure", ResultContext.fail("result.fail").getDesc());
        assertEquals("Success", PageView.empty().getDesc());
    }

    @Test
    void explicitFactoriesKeepRequestLanguageAcrossWorkerThreads() throws Exception {
        Locale requestLocale = Locale.US;
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
                try {
                    var result = ResultContext.success("data", ResultCodeEnum.SUCCESS, requestLocale);
                    assertEquals("Success", result.getDesc());
                    assertEquals("data", result.getData());
                    assertEquals("Failure", ResultContext.fail(ResultCodeEnum.FAIL, requestLocale).getDesc());
                    assertEquals("Success", ResultContext.success("data", ResultCodeEnum.SUCCESS,
                            "result.success", requestLocale).getDesc());
                    assertEquals("Failure", ResultContext.fail(ResultCodeEnum.FAIL,
                            "result.fail", requestLocale).getDesc());
                    assertEquals("Success", PageView.success(List.of("record"), requestLocale).getDesc());
                    assertEquals("Success", PageView.success(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(),
                            requestLocale).getDesc());
                    assertEquals("Success", PageView.empty(requestLocale).getDesc());
                    assertEquals("Failure", PageView.fail(ResultCodeEnum.FAIL, requestLocale).getDesc());
                    assertEquals("Failure", PageView.fail(ResultCodeEnum.FAIL, "result.fail", requestLocale).getDesc());
                    assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleContextHolder.getLocale());
                } finally {
                    LocaleContextHolder.resetLocaleContext();
                }
            }).get();
        }
    }

    @Test
    void exceptionResponsesUseExplicitLanguageAndRetainSafeCode() {
        var error = new BusinessException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET, "password=TEST_SECRET");
        var result = ResultContext.exception(error, Locale.US);
        var page = PageView.exception(new RuntimeException("password=TEST_SECRET", error), Locale.US);
        assertEquals("205035", result.getCode());
        assertEquals("205035", page.getCode());
        assertEquals("The available budget is insufficient", result.getDesc());
        assertEquals(result.getDesc(), page.getDesc());
        assertFalse(result.getSuccess());
        assertFalse(page.getSuccess());
        assertFalse(result.getDesc().contains("TEST_SECRET"));
    }

    @Test
    void cacheReadingDoesNotRetranslateExistingDescriptions() {
        var mapper = SerializerFactory.buildJsonMapperWithTypeProperty();
        var original = ResultContext.success("data").setDesc("result.success");
        String json = mapper.writeValueAsString(original);
        LocaleContextHolder.setLocale(Locale.US);
        var restored = assertInstanceOf(ResultContext.class, mapper.readValue(json, Object.class));
        assertEquals("result.success", restored.getDesc());
        assertEquals("data", restored.getData());
    }
}
