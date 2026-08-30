package com.arte.core.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 国际化配置：简体中文 / 繁体中文 / 英语
 *
 * <p>资源文件位于 arte-core 的 classpath：i18n/messages*.properties，
 * 语言通过请求头 Accept-Language 协商，未声明时回退简体中文。
 *
 * <p>注意：不能在配置类上用 @Autowired 方法注入 MessageSource 来初始化 MessageUtils——
 * 容器 refresh 早期（initMessageSource）会先创建 messageSource，进而创建本配置类，
 * 此时反向依赖 messageSource 会形成循环（BeanCurrentlyInCreationException）。
 * 因此在 messageSource 的 @Bean 工厂方法内直接交给 MessageUtils。
 *
 * @author haiqingd
 * @since 2026/8/30
 */
@Configuration
public class I18nConfig {

    /** 默认语言：简体中文（无 Accept-Language 头、非请求线程时的语言） */
    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        // 工厂方法内直接注册，不经过依赖注入，避免与配置类形成循环创建
        MessageUtils.setMessageSource(messageSource);
        // 非 Web 线程（定时任务、异步等）统一使用默认语言
        LocaleContextHolder.setDefaultLocale(DEFAULT_LOCALE);
        return messageSource;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.US));
        return resolver;
    }
}
