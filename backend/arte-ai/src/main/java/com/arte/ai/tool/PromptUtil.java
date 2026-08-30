package com.arte.ai.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.arte.ai.common.constant.PromptConstant.*;

/**
 * 提示词工具类
 *
 * <p>负责将提示词模板中的占位符替换为实际内容，支持默认模板与用户自定义模板两种路径。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/25 14:27 ✾
 */
public final class PromptUtil {

    private PromptUtil() {
    }

    /**
     * 替换 RAG 提示词模板
     *
     * @param ragContent      RAG 检索到的内容
     * @param userRagTemplate 用户自定义模板，为空时使用默认模板
     * @return 替换后的提示词
     */
    public static String replaceRag(String ragContent, String userRagTemplate) {
        String template = StrUtil.blankToDefault(userRagTemplate, RAG_PROMPT_TEMPLATE);
        return template.replace(RAG_PLACEHOLDER, ragContent);
    }

    /**
     * 替换摘要提示词模板（通用 KV 参数）
     *
     * @param summaryParam 摘要参数，key 为字段名，value 为字段值
     * @return 替换后的提示词
     */
    public static String replaceSummary(Map<String, String> summaryParam) {
        String metaInfo = buildMetaInfoBlock(summaryParam);
        return SUMMARIZE_TEMPLATE.replace(META_INFO, metaInfo);
    }

    /**
     * 替换文章摘要提示词模板
     *
     * @param title              文章标题
     * @param tags               文章标签
     * @param content            文章内容
     * @param characterCountCeil 摘要字数上限，可为 null
     * @param userTemplate       用户自定义模板，为空时使用默认模板
     * @return 替换后的提示词
     */
    public static String replaceArticleSummary(String title, List<String> tags, String content,
                                               Integer characterCountCeil, String userTemplate) {
        if (StrUtil.isNotBlank(userTemplate)) {
            return replaceArticlePrompt(title, tags, content, characterCountCeil, userTemplate);
        }
        Map<String, String> metaInfo = buildArticleMetaInfo(title, tags, content);
        if (Objects.nonNull(characterCountCeil)) {
            metaInfo.put("总结字数约", characterCountCeil.toString());
        }
        return replaceSummary(metaInfo);
    }

    /**
     * 替换文章润色提示词模板
     *
     * @param originalText       待润色原文
     * @param title              文章标题
     * @param tags               文章标签
     * @param metaContent        元信息内容（标题/标签/简介等背景）
     * @param characterCountCeil 润色后字数上限，可为 null
     * @param userTemplate       用户自定义模板，为空时使用默认模板
     * @return 替换后的提示词
     */
    public static String replacePolish(String originalText, String title, List<String> tags,
                                       String metaContent, Integer characterCountCeil, String userTemplate) {
        if (StrUtil.isNotBlank(userTemplate)) {
            userTemplate = userTemplate.replace(ORIGINAL_TEXT, StrUtil.trimToEmpty(originalText));
            return replaceArticlePrompt(title, tags, metaContent, characterCountCeil, userTemplate);
        }

        Map<String, String> extraParam = buildArticleMetaInfo(title, tags, metaContent);
        if (Objects.nonNull(characterCountCeil)) {
            extraParam.put("生成字数约", characterCountCeil.toString());
        }
        String extraInfo = buildMetaInfoBlock(extraParam);

        return new PromptReplacer(POLISH_TEMPLATE)
                .replace(META_INFO, StrUtil.trimToEmpty(originalText))
                .replace(EXTRA_INFO, extraInfo)
                .replace(CHARACTER_COUNT_CEIL, formatCountCeil(characterCountCeil))
                .get();
    }

    /**
     * 替换文章续写提示词模板
     *
     * <p>{@code originalText} 为前端拼好的补全上下文，其中用
     * {@link com.arte.ai.common.constant.PromptConstant#CURSOR_MARK} 标出待补全的位置。</p>
     *
     * @param originalText       补全上下文，如 {@code "当我们走近田野，<|cursor|>，你会发现原来生活是如此纯粹"}
     * @param title              文章标题
     * @param tags               文章标签
     * @param characterCountCeil 补全字数上限，可为 null
     * @param userTemplate       用户自定义模板，为空时使用默认模板
     * @return 替换后的提示词
     */
    public static String replaceContinuation(String originalText, String title, List<String> tags,
                                             Integer characterCountCeil, String userTemplate) {
        // 上下文为空或不含光标标记时，默认在末尾续写
        String context = normalizeContinuationContext(originalText);

        if (StrUtil.isNotBlank(userTemplate)) {
            userTemplate = userTemplate.replace(ORIGINAL_TEXT, context);
            userTemplate = userTemplate.replace(CURSOR_MARK_PLACEHOLDER, CURSOR_MARK);
            return replaceArticlePrompt(title, tags, "", characterCountCeil, userTemplate);
        }

        Map<String, String> extraParam = buildArticleMetaInfo(title, tags, null);
        String extraInfo = buildMetaInfoBlock(extraParam);

        return new PromptReplacer(CONTINUATION_TEMPLATE)
                .replace(ORIGINAL_TEXT, context)
                .replace(CURSOR_MARK_PLACEHOLDER, CURSOR_MARK)
                .replace(CHARACTER_COUNT_CEIL, formatCountCeil(characterCountCeil))
                .replace(EXTRA_INFO, extraInfo)
                .get();
    }

    /**
     * 替换用户自定义模板中的文章相关占位符
     *
     * @param title              文章标题
     * @param tags               文章标签
     * @param content            文章内容
     * @param characterCountCeil 字数上限，可为 null
     * @param userTemplate       用户自定义模板
     * @return 替换后的提示词
     */
    public static String replaceArticlePrompt(String title, List<String> tags, String content,
                                              Integer characterCountCeil, String userTemplate) {
        return new PromptReplacer(userTemplate)
                .replace(TITLE, StrUtil.trimToEmpty(title))
                .replace(TAGS, CollUtil.isNotEmpty(tags) ? StrUtil.join(", ", tags) : StrUtil.EMPTY)
                .replace(CONTENT, StrUtil.trimToEmpty(content))
                .replace(CHARACTER_COUNT_CEIL, formatCountCeil(characterCountCeil))
                .get();
    }

    /**
     * 构建文章元信息 Map（有序，保证拼接顺序确定）
     *
     * @param title              文章标题
     * @param tags               文章标签
     * @param content            文章内容，为空时不加入
     * @return 有序元信息 Map
     */
    public static Map<String, String> buildArticleMetaInfo(String title, List<String> tags, String content) {
        // 使用 LinkedHashMap 保证字段顺序：标题 → 标签 → 内容 → 字数
        Map<String, String> param = new LinkedHashMap<>(4);
        if (StrUtil.isNotBlank(title)) {
            param.put("标题", title);
        }
        if (CollUtil.isNotEmpty(tags)) {
            param.put("文章标签", StrUtil.join(", ", tags));
        }
        if (StrUtil.isNotBlank(content)) {
            param.put("文章内容", content);
        }
        return param;
    }

    /**
     * 构建可直接放入提示词的文章参考信息。
     *
     * @param title   文章标题
     * @param tags    文章标签
     * @param content 文章内容
     * @return 格式化后的文章信息
     */
    public static String buildArticleInfo(String title, List<String> tags, String content) {
        return buildMetaInfoBlock(buildArticleMetaInfo(title, tags, content));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 将 KV 参数 Map 格式化为提示词中的列表块
     * <pre>
     * - 标题：xxx
     * - 文章标签：xxx
     * </pre>
     */
    private static String buildMetaInfoBlock(Map<String, String> param) {
        if (param == null || param.isEmpty()) {
            return StrUtil.EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        param.forEach((key, value) -> sb.append("- ").append(key).append("：").append(value).append('\n'));
        return sb.toString();
    }

    /**
     * 将字数上限格式化为字符串，null 时返回空字符串
     */
    private static String formatCountCeil(Integer characterCountCeil) {
        return Objects.isNull(characterCountCeil) ? StrUtil.EMPTY : characterCountCeil.toString();
    }

    /**
     * 规范化续写上下文：确保文本中包含光标标记
     * <ul>
     *   <li>原文为空 → 仅光标标记</li>
     *   <li>原文不含光标标记 → 追加到末尾</li>
     * </ul>
     */
    private static String normalizeContinuationContext(String originalText) {
        if (StrUtil.isBlank(originalText)) {
            return CURSOR_MARK;
        }
        if (!originalText.contains(CURSOR_MARK)) {
            return originalText + CURSOR_MARK;
        }
        return originalText;
    }

    // ==================== 内部辅助类 ====================

    /**
     * 链式提示词占位符替换器，避免多次临时变量赋值
     */
    private static class PromptReplacer {

        private String text;

        PromptReplacer(String template) {
            this.text = template;
        }

        PromptReplacer replace(String placeholder, String value) {
            if (StrUtil.isNotEmpty(placeholder) && text.contains(placeholder)) {
                text = text.replace(placeholder, value);
            }
            return this;
        }

        String get() {
            return text;
        }
    }
}
