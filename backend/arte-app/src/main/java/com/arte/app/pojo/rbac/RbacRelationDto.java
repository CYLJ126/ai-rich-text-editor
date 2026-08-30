package com.arte.app.pojo.rbac;

import com.arte.core.annotations.MybatisParams;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * RBAC 关系实体
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@MybatisParams(value = "arte_rbac_relation", queryFields = {}, insertFields = {MybatisParams.CREATE_BY, MybatisParams.CREATE_TIME})
public class RbacRelationDto extends RbacRelationPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -2014313548092281698L;

}
