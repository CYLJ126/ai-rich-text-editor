package com.arte.core.i18n;

import cn.hutool.core.text.CharSequenceUtil;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 国际化消息工具
 *
 * <p>调用方传入的 keyOrText 既可以是 messages*.properties 中定义的 message key，
 * 也可以是任意未收录的原始文本（如尚未迁移的历史文案）：查不到 key 时原样返回，
 * 因此存量硬编码文案无需一次性迁移完毕即可平滑接入。
 *
 * <p>当前语言由 {@link LocaleContextHolder} 提供：Web 请求线程由 DispatcherServlet
 * 根据 LocaleResolver（Accept-Language）解析；非请求线程（定时任务、异步等）
 * 使用 {@link I18nConfig} 设置的默认 locale（简体中文）。
 *
 * @author haiqingd
 * @since 2026/8/30
 */
public final class MessageUtils {

    private static volatile MessageSource messageSource;

    private MessageUtils() {
    }

    public static void setMessageSource(MessageSource source) {
        messageSource = source;
    }

    /**
     * 按当前语言翻译消息
     *
     * @param keyOrText message key，或未收录的原始文本（原样返回）
     * @param args      MessageFormat 占位参数，对应资源文件中的 {0}、{1}...
     * @return 翻译后的文案；MessageSource 未初始化（如单测环境）时返回原文
     */
    public static String get(String keyOrText, Object... args) {
        if (CharSequenceUtil.isBlank(keyOrText)) {
            return keyOrText;
        }
        MessageSource source = messageSource;
        if (source == null) {
            return keyOrText;
        }
        try {
            return source.getMessage(keyOrText, args, keyOrText, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return keyOrText;
        }
    }
}
