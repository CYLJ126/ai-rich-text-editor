package com.arte.ainew.application.conversation;

import com.arte.ainew.pojo.generation.ChatMessage;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java 21 \X 按扩展字素簇截断，保留 emoji、组合音标与 ZWJ 序列。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 09:28 ✾
 */
public final class ConversationText {

    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private ConversationText() {
    }

    public static String question(ChatMessage message) {
        return message.content().stream().filter(ChatMessage.Text.class::isInstance)
                .map(ChatMessage.Text.class::cast).map(ChatMessage.Text::text).collect(Collectors.joining("\n"));
    }

    public static String shorten(String text, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive limit required");
        var matcher = GRAPHEME.matcher(text);
        int count = 0, end = 0;
        while (matcher.find()) {
            if (++count > limit) return text.substring(0, end) + "……";
            end = matcher.end();
        }
        return text;
    }
}
