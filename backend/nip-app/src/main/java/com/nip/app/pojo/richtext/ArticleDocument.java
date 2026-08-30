package com.nip.app.pojo.richtext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

/**
 * 文章索引实体 —— 对应 articles 索引，仅存储文章元数据，不含正文内容
 * <p>
 * 用途：文章列表页搜索、按条件过滤/排序、搜索结果展示
 * 不含：contentJson / contentMd（存于 MySQL），tag_names（运行时翻译）
 * </p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:18 ✾
 **/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArticleDocument {

    /**
     * 文章 ID，对应 MySQL 主键
     */
    @JsonProperty("article_id")
    private Integer articleId;

    /**
     * 文章标题
     * <p>ES mapping: text，ik_max_word 索引 + ik_smart 搜索，支持标题全文检索</p>
     */
    @JsonProperty("title")
    private String title;

    /**
     * 文章摘要，用于搜索结果卡片展示及全文检索
     * <p>ES mapping: text，ik_max_word 索引 + ik_smart 搜索</p>
     */
    @JsonProperty("summary")
    private String summary;

    /**
     * 作者用户名（冗余存储，避免回查 MySQL）
     * <p>ES mapping: keyword，支持精确过滤</p>
     */
    @JsonProperty("author")
    private String author;

    /**
     * 所属目录 ID，用于目录维度的过滤
     * <p>ES mapping: integer</p>
     */
    @JsonProperty("catalog_id")
    private Integer catalogId;

    /**
     * 标签 ID 列表，用于标签过滤（terms query）
     * <p>tag_names 不存 ES，展示时由 tag_id 在 Redis/MySQL 翻译</p>
     * <p>ES mapping: integer</p>
     */
    @JsonProperty("tag_ids")
    private Set<Integer> tagIds;

    /**
     * 文章类型，如 ARTICLE / WIKI / NOTE 等
     * <p>ES mapping: keyword，支持精确过滤</p>
     */
    @JsonProperty("article_type")
    private String articleType;

    /**
     * 访问级别，如 PUBLIC / PRIVATE / TEAM 等
     * <p>ES mapping: keyword，权限过滤必用字段</p>
     */
    @JsonProperty("access_level")
    private String accessLevel;

    /**
     * 是否公开，冗余字段，配合 access_level 快速过滤
     * <p>ES mapping: boolean</p>
     */
    @JsonProperty("is_public")
    private Boolean isPublic;

    /**
     * 创建人用户名
     * <p>ES mapping: keyword，用于"我的文章"等个人维度过滤</p>
     */
    @JsonProperty("create_by")
    private String createBy;

    /**
     * 最后更新人用户名
     * <p>ES mapping: keyword</p>
     */
    @JsonProperty("update_by")
    private String updateBy;

    /**
     * 文章字数，可用于"只看长文/短文"等字数范围过滤
     * <p>ES mapping: integer</p>
     */
    @JsonProperty("character_count")
    private Integer characterCount;

    /**
     * 乐观锁版本号，用于 ES 与 MySQL 数据一致性校验
     * <p>ES mapping: integer</p>
     */
    @JsonProperty("row_version")
    private Integer rowVersion;

    /**
     * 封面图 URL，纯展示字段，不参与检索
     * <p>ES mapping: keyword，index: false</p>
     */
    @JsonProperty("cover")
    private String cover;

    /**
     * 创建时间
     * <p>ES mapping: date</p>
     */
    @JsonProperty("create_time")
    private Instant createTime;

    /**
     * 最后更新时间，支持按时间排序和范围过滤
     * <p>ES mapping: date</p>
     */
    @JsonProperty("update_time")
    private Instant updateTime;
}