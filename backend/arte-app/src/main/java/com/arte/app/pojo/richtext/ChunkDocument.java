package com.arte.app.pojo.richtext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.arte.app.common.enums.richtext.TiptapNodeTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * ES 分块文档，对应 chunks 索引 mapping
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 19:59 ✾
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChunkDocument {
    /**
     * 分块 ID
     */
    @JsonProperty("chunk_id")
    private String chunkId;
    /**
     * 文章元数据，和分块一起存储，虽有冗余数据，但方便检索
     */
    @JsonProperty("article_meta")
    private ArticleDocument articleMeta;
    /**
     * 对应的文章 ID
     */
    @JsonProperty("article_id")
    private Integer articleId;
    /**
     * Tiptap 节点 ID
     */
    @JsonProperty("tiptap_node_id")
    private String tiptapNodeId;
    /**
     * chunk 跨越多个节点时记录所有节点ID
     */
    @JsonProperty("tiptap_node_ids")
    private List<String> tiptapNodeIds;
    /**
     * paragraph / heading / code_block / table / mermaid / section_summary
     */
    @JsonProperty("chunk_type")
    private TiptapNodeTypeEnum chunkType;
    /**
     * 纯文本内容
     */
    @JsonProperty("content")
    private String content;
    /**
     * 拼接了面包屑的完整内容，用于送入LLM
     */
    @JsonProperty("content_with_breadcrumb")
    private String contentWithBreadcrumb;
    /**
     * 面包屑: 文章标题 > H2 > H3
     */
    @JsonProperty("breadcrumb")
    private String breadcrumb;
    /**
     * 加粗词汇，检索时 boost
     */
    @JsonProperty("bold_terms")
    private String boldTerms;
    /**
     * 高亮词汇，检索时 boost
     */
    @JsonProperty("highlight_terms")
    private String highlightTerms;
    /**
     * 含删除线内容，降权标记
     */
    @JsonProperty("has_strikethrough")
    private Boolean hasStrikethrough;
    /**
     * 章节标题
     */
    @JsonProperty("section_heading")
    private String sectionHeading;
    /**
     * 章节 ID
     */
    @JsonProperty("section_heading_id")
    private String sectionHeadingId;
    /**
     * 章节级别，如一级标题、二级标题……
     */
    @JsonProperty("heading_level")
    private Integer headingLevel;
    /**
     * 分块索引
     */
    @JsonProperty("chunk_index")
    private Integer chunkIndex;
    /**
     * 前一分块 ID
     */
    @JsonProperty("prev_chunk_id")
    private String prevChunkId;
    /**
     * 后一分块 ID
     */
    @JsonProperty("next_chunk_id")
    private String nextChunkId;
    /**
     * token 数量
     */
    @JsonProperty("token_count")
    private Integer tokenCount;
    /**
     * 重叠 token 数量
     */
    @JsonProperty("overlap_tokens")
    private Integer overlapTokens;
    /**
     * 向量，由外部 embedding 服务填充
     */
    @JsonProperty("embedding")
    private float[] embedding;
    /**
     * 媒体引用，如 [图片: alt]
     */
    @JsonProperty("media_refs")
    private List<String> mediaRefs;
    /**
     * 代码块语言，如 python、 java 等
     */
    @JsonProperty("code_language")
    private String codeLanguage;
    /**
     * 创建时间
     */
    @JsonProperty("create_time")
    private Instant createTime;
    /**
     * 更新时间
     */
    @JsonProperty("update_time")
    private Instant updateTime;
}
