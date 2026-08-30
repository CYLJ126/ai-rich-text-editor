package com.arte.app.pojo.richtext;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Set;

/**
 * 文章实体类
 Mapper 上设置了忽略自动设置字段，故所有文章的 mybatis 操作都要注意手动设置创建人、创建时间、更新人、更新时间
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/5 14:48 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ArticleDto extends ArticlePo implements Serializable {

    @Serial
    private static final long serialVersionUID = -8800189326598066346L;

    /**
     * 当前用户的文章权限
     */
    @TableField(exist = false)
    private String effectivePermission;

    /**
     * 搜索文本
     */
    @TableField(exist = false)
    private String searchBingoText;

    /**
     * 标签 ID 列表
     */
    @TableField(exist = false)
    private Set<Integer> tags;

    public ArticleDocument toArticleDocument() {
        return ArticleDocument.builder()
                .articleId(getId())
                .title(getTitle())
                .summary(getSummary())
                .author(getAuthor())
                .catalogId(getCatalogId())
                .characterCount(getCharacterCount())
                .cover(getCover())
                .tagIds(CollUtil.isNotEmpty(getTags()) ? getTags() : Collections.emptySet())
                .articleType(getArticleType().getValue())
                .accessLevel(getAccessLevel().getValue())
                .isPublic(getIsPublic())
                .createBy(getCreateBy())
                .updateBy(getUpdateBy())
                .createTime(getCreateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant())
                .updateTime(getUpdateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant())
                .rowVersion(getRowVersion())
                .build();


    }
}
