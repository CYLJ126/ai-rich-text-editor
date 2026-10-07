package com.arte.app.web.advice;

import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.BusinessException;
import com.arte.core.i18n.I18nConfig;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.serialize.SerializerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        MessageUtils.setMessageSource(messages);
        mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setLocaleResolver(new I18nConfig().localeResolver())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        SerializerFactory.buildJsonMapperWithoutTypeProperty()))
                .build();
    }

    @AfterEach
    void cleanup() {
        MessageUtils.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void businessErrorUsesAcceptLanguage() throws Exception {
        String body = mvc.perform(get("/locale/business").header("Accept-Language", "en-US"))
                .andReturn().getResponse().getContentAsString();
        var json = SerializerFactory.buildJsonMapperWithoutTypeProperty().readTree(body);
        assertEquals("205035", json.get("code").asString());
        assertEquals("The available budget is insufficient", json.get("desc").asString());
        assertFalse(json.get("success").asBoolean());
    }

    @Test
    void handlerUsesExplicitRequestLocaleEvenWhenThreadLocaleDiffers() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        var result = new GlobalExceptionHandler().handleCommonException(
                new BusinessException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET), Locale.US);
        assertEquals("The available budget is insufficient", result.getDesc());
        assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleContextHolder.getLocale());
    }

    @Test
    void unknownErrorRemainsSafeAndUsesRequestLanguage() throws Exception {
        String body = mvc.perform(get("/locale/system").header("Accept-Language", "en-US"))
                .andReturn().getResponse().getContentAsString();
        var json = SerializerFactory.buildJsonMapperWithoutTypeProperty().readTree(body);
        assertEquals(ResultCodeEnum.SYSTEM_EXCEPTION.getCode(), json.get("code").asString());
        assertEquals(ResultCodeEnum.SYSTEM_EXCEPTION.getDesc(Locale.US), json.get("desc").asString());
        assertFalse(body.contains("TEST_SECRET"));
    }

    @RestController
    static class FailingController {
        @GetMapping("/locale/business")
        public String business() {
            throw new BusinessException(ResultCodeEnum.AI_INSUFFICIENT_BUDGET);
        }

        @GetMapping("/locale/system")
        public String system() {
            throw new RuntimeException("password=TEST_SECRET");
        }
    }
}
