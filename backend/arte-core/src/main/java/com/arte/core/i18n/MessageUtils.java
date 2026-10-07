package com.arte.core.i18n;

import cn.hutool.core.text.CharSequenceUtil;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

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
 * 异步或响应式调用应在 HTTP 入口取得语言，再使用显式 Locale 重载，避免依赖工作线程的语言。
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
        return get(LocaleContextHolder.getLocale(), keyOrText, args);
    }

    /**
     * 按指定语言翻译，不读取或修改当前线程的语言。
     * Locale 放在首位，避免与现有消息占位参数的可变参数重载混淆。
     *
     * @param locale 请求语言；null 使用系统默认简体中文
     * @param keyOrText message key，或未收录的原始文本
     * @param args MessageFormat 占位参数
     * @return 翻译后的文案；未收录或消息源未初始化时返回原文
     */
    public static String get(Locale locale, String keyOrText, Object... args) {
        if (CharSequenceUtil.isBlank(keyOrText)) {
            return keyOrText;
        }
        MessageSource source = messageSource;
        if (source == null) {
            return keyOrText;
        }
        try {
            return source.getMessage(keyOrText, args, keyOrText,
                    locale == null ? I18nConfig.DEFAULT_LOCALE : locale);
        } catch (Exception e) {
            return keyOrText;
        }
    }
}
