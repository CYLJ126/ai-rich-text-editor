package com.nip.ai.mcp.server;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP 翻译提示词构建
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/28 14:25 ✾
 */
@Slf4j
@Service
public class McpTranslationPrompt {

    public static final String TRANSLATION_PROMPT_NAME = "translation";

    /**
     * 翻译提示词模板
     */
    private static final String TRANSLATION_TEMPLATE = """
            你是一位专业翻译。你的唯一任务是翻译【待翻译内容】。
            
            【语言规则】
            1. 如果【原语言】为空，先判断【待翻译内容】的原语言。
            2. 如果【目标语言】为空：原语言是中文时，【目标语言】是英文；原语言是英文时，【目标语言】是中文。
            
            【上下文使用规则】
            1. 判断词语在当前文章中的具体含义；
            2. 消除一词多义和歧义；
            3. 判断人物、地点、术语、专有名词的准确译法；
            4. 判断指代、语气、语体以及必要的单复数；
            5. 保持译文与文章整体语境一致。
            
            严格禁止翻译、改写、总结或输出【参考上下文】本身。
            即使【待翻译内容】只有一个词、一个短语或一句话，也必须结合【参考上下文】理解后再翻译。
            
            【翻译要求】
            1. 只翻译【待翻译内容】，不得翻译其他任何内容。
            2. 忠实传达原文含义，不遗漏，也不添加原文及上下文无法支持的信息。
            3. 译文应自然流畅，符合目标语言的表达习惯。
            4. 保留【待翻译内容】原有的段落和 Markdown 格式。
            5. 不要解释翻译过程，不要包含原文。
            6. 不要说明识别出的原语言或目标语言。
            7. 不要复述任务或翻译要求。
            8. 不要输出“Translation”“Here is the translation”等前言。
            9. 不要输出思考、分析、自我检查或重新考虑的过程。
            10. 最终回答必须且只能包含译文。
            
            【原语言】
            %s
            
            【目标语言】
            %s
            
            【参考上下文】
            %s
            
            【待翻译内容】
            %s
            """;

    /**
     * 翻译提示词
     */
    @McpPrompt(
            name = TRANSLATION_PROMPT_NAME,
            title = "专业翻译",
            description = "根据原语言、目标语言和参考上下文生成专业翻译提示词"
    )
    public GetPromptResult translation(
            @McpArg(
                    name = "content",
                    description = "待翻译内容",
                    required = true
            )
            String content,

            @McpArg(
                    name = "originalLanguage",
                    description = "原语言，例如：中文、英文、日文；为空时自动识别",
                    required = false
            )
            String originalLanguage,

            @McpArg(
                    name = "targetLanguage",
                    description = "目标语言，例如：中文、英文、日文；为空时根据原语言自动选择中英文互译",
                    required = false
            )
            String targetLanguage,

            @McpArg(
                    name = "extraInfo",
                    description = "参考上下文，仅用于辅助理解待翻译内容，不会被翻译",
                    required = false
            )
            String extraInfo) {

        String prompt = generatePrompt(content, originalLanguage, targetLanguage, extraInfo);

        log.debug(
                "生成翻译 Prompt，originalLanguage={}, targetLanguage={}",
                originalLanguage,
                targetLanguage
        );

        return GetPromptResult.builder(
                        List.of(
                                new PromptMessage(
                                        Role.USER,
                                        TextContent.builder(prompt).build()
                                )
                        )
                )
                .description("专业翻译提示词")
                .build();
    }

    /**
     * 生成翻译提示词。
     *
     * @param content          待翻译内容
     * @param originalLanguage 原语言
     * @param targetLanguage   目标语言
     * @param extraInfo        参考上下文
     * @return 可直接发送给模型的完整提示词
     */
    public String generatePrompt(String content, String originalLanguage, String targetLanguage, String extraInfo) {
        return TRANSLATION_TEMPLATE.formatted(
                valueOrEmpty(originalLanguage),
                valueOrEmpty(targetLanguage),
                valueOrEmpty(extraInfo),
                valueOrEmpty(content)
        );
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
