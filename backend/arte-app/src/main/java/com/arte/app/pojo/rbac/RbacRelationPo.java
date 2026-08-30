package com.arte.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.common.enums.RbacRelationEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RBAC 关系实体
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("arte_rbac_relation")
public class RbacRelationPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 6299159418607937544L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 源对象
     */
    private String source;

    /**
     * 绑定对象
     */
    private String target;

    /**
     * 绑定类型
     */
    private RbacRelationEnum bindingType;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


    public static final String COL_ID = "id";

    public static final String COL_SOURCE = "source";

    public static final String COL_TARGET = "target";

    public static final String COL_BINDING_TYPE = "binding_type";

    public static final String COL_CREATE_BY = "create_by";

    public static final String COL_CREATE_TIME = "create_time";

}
