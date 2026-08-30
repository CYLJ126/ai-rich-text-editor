package com.arte.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 目录传输对象（带树形结构）
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class CatalogDto extends CatalogPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -4111714308768764220L;

    /**
     * 子目录列表
     */
    @TableField(exist = false)
    private List<CatalogDto> children;

    /**
     * 该目录下的文章数量
     */
    @TableField(exist = false)
    private Integer articleCount;

    /**
     * 该目录下的文章列表
     */
    @TableField(exist = false)
    private List<ArticleDto> articles;

    /** Current user's effective catalog permission. */
    @TableField(exist = false)
    private String effectivePermission;
}
