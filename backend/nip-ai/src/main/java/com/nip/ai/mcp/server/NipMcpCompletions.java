package com.nip.ai.mcp.server;

import io.modelcontextprotocol.spec.McpSchema.CompleteRequest.CompleteArgument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MCP 补全
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/28 17:11 ✾
 **/
@Slf4j
@Service
public class NipMcpCompletions {

    private static final Set<String> LANGUAGE_ARGUMENT_NAMES = Set.of(
            "originalLanguage",
            "targetLanguage"
    );

    private static final List<String> SUPPORTED_LANGUAGES = List.of(
            "中文",
            "英文",
            "日文",
            "韩文",
            "法文",
            "德文",
            "西班牙文",
            "葡萄牙文",
            "意大利文",
            "俄文"
    );

    /**
     * 为翻译 Prompt 的原语言和目标语言参数提供候选值。
     *
     * @param argument 当前正在补全的参数及用户已输入的前缀
     * @return 与前缀匹配的语言名称
     */
    @McpComplete(prompt = McpTranslationPrompt.TRANSLATION_PROMPT_NAME)
    public List<String> completeTranslationLanguage(CompleteArgument argument) {
        if (!LANGUAGE_ARGUMENT_NAMES.contains(argument.name())) {
            return List.of();
        }

        String prefix = argument.value().strip().toLowerCase(Locale.ROOT);
        List<String> matches = SUPPORTED_LANGUAGES.stream()
                .filter(language -> language.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();

        log.info("翻译语言补全，参数：{}，前缀：{}，候选值：{}", argument.name(), argument.value(), matches);
        return matches;
    }
}
