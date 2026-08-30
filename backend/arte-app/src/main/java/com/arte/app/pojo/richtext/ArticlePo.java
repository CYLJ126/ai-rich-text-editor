package com.arte.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.common.enums.richtext.ArticleAccessLevelEnum;
import com.arte.app.common.enums.richtext.ArticleTypeEnum;
import com.arte.app.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文章实体类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/5 12:50 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_rt_article")
public class ArticlePo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = -6372170466973035080L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 作者
     */
    private String author;

    /**
     * 标题
     */
    private String title;

    /**
     * 摘要
     */
    private String summary;

    /**
     * 封面图片URL
     */
    private String cover;

    /**
     * 所属目录ID
     */
    private Integer catalogId;

    /**
     * 文章字符数
     */
    private Integer characterCount;

    /**
     * 目录内排序序号
     */
    private Integer orderId;

    /**
     * Json 文本
     */
    private String contentJson;

    /**
     * markdown 纯文本
     */
    private String contentMd;

    /**
     * 纯文本（用于 Elasticsearch 检索）
     */
    private String contentText;

    /**
     * 是否公开
     */
    private Boolean isPublic;

    /**
     * 是否删除（逻辑删除）
     */
    @TableLogic
    private Boolean isDelete;

    /**
     * 访问等级
     */
    private ArticleAccessLevelEnum accessLevel;

    /**
     * 文章类型
     */
    private ArticleTypeEnum articleType;

    /**
     * 版本号，乐观锁
     */
    private Integer rowVersion;

    public static final String COL_ID = "id";
    public static final String COL_AUTHOR = "author";
    public static final String COL_TITLE = "title";
    public static final String COL_SUMMARY = "summary";
    public static final String COL_COVER = "cover";
    public static final String COL_CATALOG_ID = "catalog_id";
    public static final String COL_ORDER_ID = "order_id";
    public static final String COL_CONTENT_JSON = "content_json";
    public static final String COL_CONTENT_MD = "content_md";
    public static final String COL_CONTENT_TEXT = "content_text";
    public static final String COL_IS_DELETE = "is_delete";
    public static final String COL_CHARACTER_COUNT = "character_count";
    public static final String COL_ACCESS_LEVEL = "access_level";
    public static final String COL_ARTICLE_TYPE = "article_type";
    public static final String COL_ROW_VERSION = "row_version";

}
