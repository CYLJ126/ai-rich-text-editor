package com.arte.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章批注评论实体
 *
 * @author Codex
 * @since 2026/6/20
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("arte_rt_article_comment")
public class ArticleCommentPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 所属文章 ID */
    private Integer articleId;

    /** 所属批注线程 ID */
    private String threadId;

    /** 评论 ID */
    private String commentId;

    /** 评论内容 */
    private String content;

    /** 删除时间，非空表示评论已删除 */
    private LocalDateTime deletedAt;

    /** 是否删除 */
    @TableLogic
    private Boolean isDelete;

    public static final String COL_ID = "id";
    public static final String COL_ARTICLE_ID = "article_id";
    public static final String COL_THREAD_ID = "thread_id";
    public static final String COL_COMMENT_ID = "comment_id";
    public static final String COL_CONTENT = "content";
    public static final String COL_DELETED_AT = "deleted_at";
}
