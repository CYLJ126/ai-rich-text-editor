package com.arte.app.pojo.base;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * <p>
 * 标签表
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class TagDto extends TagPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 2133207138512629015L;

    @TableField(exist = false)
    private List<TagDto> children;

    /**
     * 是否选中，根据标签关联关系，存在关联关系则选中
     */
    @TableField(exist = false)
    private Boolean checked;
}
