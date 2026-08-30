package com.nip.ai.common.constant;

/**
 * 提示词占位符与模板常量
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/30 10:05 ✾
 */
public final class PromptConstant {

    private PromptConstant() {
    }

    // ==================== 占位符 ====================

    /**
     * RAG 内容占位符
     */
    public static final String RAG_PLACEHOLDER = "$RAG$";
    /** 原始文本占位符 */
    public static final String ORIGINAL_TEXT = "$ORIGINAL_TEXT$";
    /** 元数据占位符 */
    public static final String META_INFO = "$META_INFO$";
    /** 额外信息占位符 */
    public static final String EXTRA_INFO = "$EXTRA_INFO$";
    /** 标题占位符 */
    public static final String TITLE = "$TITLE$";
    /** 标签占位符 */
    public static final String TAGS = "$TAGS$";
    /** 内容占位符 */
    public static final String CONTENT = "$CONTENT$";
    /** 字符数上限占位符 */
    public static final String CHARACTER_COUNT_CEIL = "$CHARACTER_COUNT_CEIL$";
    /**
     * 光标标记占位符，用于在提示词模板中引用光标标记本身
     */
    public static final String CURSOR_MARK_PLACEHOLDER = "$CURSOR_MARK$";

    // ==================== 语义标记 ====================

    /**
     * 光标标记，前端在续写上下文中用它标出待补全的位置
     */
    public static final String CURSOR_MARK = "<|cursor|>";

    // ==================== 默认提示词模板 ====================

    /**
     * RAG 默认提示词模板
     */
    public static final String RAG_PROMPT_TEMPLATE = """
            请总结知识库的内容来回答问题，请列举知识库中的数据详细回答。\
            如果参考信息不足以回答问题，或与问题无关时，你的回答必须包括"知识库中未找到您要的答案！"这句话。
            如果用户的提问中包含用引号包裹起来的内容，对相似度阈值限制更加严格，只有近乎完全一致的内容才认为在知识库中存在。
            以下是知识库：
            $RAG$
            """;

    /**
     * 总结/摘要生成默认提示词模板
     */
    public static final String SUMMARIZE_TEMPLATE = """
            你是一位资深的内容编辑，擅长提炼文章核心精华，能够用简洁、准确的语言生成高信息密度的摘要。
            【输入信息】
            $META_INFO$
            【摘要要求】
            1. 准确概括文章的核心主题和主要观点，不遗漏关键信息，不添加原文没有的内容。
            2. 根据文章类型调整摘要风格，如：
               - 小说/故事类：侧重情节主线、核心冲突、人物关系，避免过度剧透关键转折
               - 笔记/干货类：提取核心结论、方法论、关键要点，按重要性排序呈现
               - 散文/随笔类：抓住情感基调和核心意象，体现作者态度与氛围
               - 评论/分析类：提炼核心论点、论证逻辑和主要结论
            3. 语言通顺自然，符合表达习惯，避免生硬堆砌关键词。
            4. 严格控制在要求的字数范围内，优先保留最重要信息。
            5. 如果原文有明显的时间线或因果关系，摘要中予以体现；或包含"核心主题"、"关键信息/情节推进"和"结论/启示"三个层面。
            6. 格式要求：仅输出摘要正文，无需前言后语；使用纯文本内容，无需添加文本格式。
            请开始生成摘要：\s
            """;

    /**
     * 文章润色默认提示词模板
     */
    public static final String POLISH_TEMPLATE = """
            你是一位资深的编辑，擅长对文章进行润色，使其语言更加通顺、流畅，符合表达习惯，同时保持原文的意思和结构。
            【待润色内容】
            $META_INFO$
            【背景知识】
            $EXTRA_INFO$
            【润色要求】
            1. 语言通顺自然，符合表达习惯，避免生硬堆砌关键词。
            2. 保持原文的意思和结构，不要改变原文的含义。
            3. 语言简洁明了，避免冗长。
            4. 避免使用网络用语和 slang，避免使用英文。
            5. 补全篇幅：$CHARACTER_COUNT_CEIL$ 字以内，以自然衔接为准，不要为凑字数而啰嗦。
            6. 返回 Markdown 格式的内容，不要添加引号、解释，使我可以直接插入到 Markdown 文本中。
            请根据用户要求开始润色：\s
            """;

    /**
     * 文章续写默认提示词模板
     */
    public static final String CONTINUATION_TEMPLATE = """
            你是一位资深的作家，擅长在文章的指定位置续写补全，使补全的内容与上下文自然衔接、风格一致。
            【待补全内容】
            $ORIGINAL_TEXT$
            【背景知识】
            $EXTRA_INFO$
            【补全要求】
            1. $CURSOR_MARK$ 标出的是待补全的位置，只输出该位置应填入的内容，不要重复上下文中已有的文字，也不要输出 $CURSOR_MARK$ 本身。
            2. 补全内容要承接前文、顺畅过渡到后文，语义连贯、逻辑通顺；若后文为空，则自然地往下写。
            3. 保持与原文一致的语言、人称、时态、语气和文风，不改变原文的立意。
            4. 只补全 $CURSOR_MARK$ 处的内容，不要修改、润色或改写上下文的其他部分。
            5. 补全篇幅：$CHARACTER_COUNT_CEIL$ 字以内，以自然衔接为准，不要为凑字数而啰嗦。
            6. 避免使用网络用语和 slang，避免使用英文。
            7. 返回 Markdown 格式的内容，不要添加引号、解释，使我可以直接插入到 Markdown 文本中。
            请根据用户要求开始补全：\s
            """;

}
