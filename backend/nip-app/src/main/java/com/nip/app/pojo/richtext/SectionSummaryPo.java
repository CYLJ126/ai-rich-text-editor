package com.nip.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 章节摘要表
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("nip_rt_section_summary")
public class SectionSummaryPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 4537639051068057326L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer articleId;

    /**
     * Tiptap heading 节点 ID
     */
    private String headingId;

    /**
     * 章节标题
     */
    private String headingText;

    /**
     * 章节摘要（LLM 生成）
     */
    private String summary;

    /**
     * 对应 ES 中的 chunk ID
     */
    private String embeddingId;

    /**
     * heading level
     */
    private Integer level;

    private Integer orderIdx;

    public static final String ID = "id";

    public static final String ARTICLE_ID = "article_id";

    public static final String HEADING_ID = "heading_id";

    public static final String HEADING_TEXT = "heading_text";

    public static final String SUMMARY = "summary";

    public static final String EMBEDDING_ID = "embedding_id";

    public static final String LEVEL = "level";

    public static final String ORDER_IDX = "order_idx";
}
