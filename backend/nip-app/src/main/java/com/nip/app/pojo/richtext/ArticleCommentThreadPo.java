package com.nip.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章批注线程实体
 *
 * @author Codex
 * @since 2026/6/20
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("nip_rt_article_comment_thread")
public class ArticleCommentThreadPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 所属文章 ID */
    private Integer articleId;

    /** 前端写入 Tiptap comments mark 的线程 ID */
    private String threadId;

    /** 解决时间，非空表示已解决 */
    private LocalDateTime resolvedAt;

    /** 是否删除 */
    @TableLogic
    private Boolean isDelete;

    public static final String COL_ID = "id";
    public static final String COL_ARTICLE_ID = "article_id";
    public static final String COL_THREAD_ID = "thread_id";
    public static final String COL_RESOLVED_AT = "resolved_at";
}
