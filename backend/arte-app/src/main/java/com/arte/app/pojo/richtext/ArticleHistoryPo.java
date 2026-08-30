package com.arte.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Article history snapshot. Only fields required for version comparison are stored.
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_rt_article_history")
public class ArticleHistoryPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Integer articleId;

    private Integer versionNo;

    private String title;

    private String content;

    private String modifiedBy;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime modifiedTime;

    public static final String COL_ARTICLE_ID = "article_id";
    public static final String COL_VERSION_NO = "version_no";
}
