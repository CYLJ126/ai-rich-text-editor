package com.nip.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 文章批注线程 DTO
 *
 * @author Codex
 * @since 2026/6/20
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ArticleCommentThreadDto extends ArticleCommentThreadPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 创建线程时的首条评论内容 */
    @TableField(exist = false)
    private String content;

    /** 线程下评论列表 */
    @TableField(exist = false)
    private List<ArticleCommentDto> comments;
}
