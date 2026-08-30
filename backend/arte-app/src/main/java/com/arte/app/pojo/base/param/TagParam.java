package com.arte.app.pojo.base.param;

import com.arte.app.common.enums.TagTypeEnum;
import com.arte.app.pojo.BaseParam;
import com.arte.app.pojo.base.TagDto;
import com.arte.core.enums.StatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * <p>
 * 标签管理查询参数对象
 * </p>
 *
 * @author zhangsc
 * @since 2026-05-30
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class TagParam extends BaseParam<TagDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = 7225184626531669258L;

    /**
     * 主键
     */
    private Integer id;

    /**
     * 标签名
     */
    private String name;

    /**
     * 状态
     */
    private StatusEnum status;

    /**
     * 描述
     */
    private String description;

    /**
     * 父 ID，无则为顶级标签
     */
    private Set<Integer> fatherIds;

    /**
     * 标签类型列表
     */
    private Set<TagTypeEnum> tagTypes;

    /**
     * 源 ID
     */
    private Integer sourceId;

}
