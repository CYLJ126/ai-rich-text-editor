package com.arte.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文章批注评论 DTO
 *
 * @author Codex
 * @since 2026/6/20
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ArticleCommentDto extends ArticleCommentPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 删除评论时是否清空内容 */
    @TableField(exist = false)
    private Boolean deleteContent;
}
