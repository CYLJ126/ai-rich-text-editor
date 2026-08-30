package com.arte.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 目录实体类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_rt_catalog")
public class CatalogPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1691420778425342377L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 目录名称
     */
    private String name;

    /**
     * 父目录ID，无则为顶级目录（文集）
     */
    private Integer fatherId;

    /**
     * 同级排序序号
     */
    private Integer orderId;

    /**
     * 描述
     */
    private String description;

    /**
     * 是否公开
     */
    private Boolean isPublic;

    /**
     * 是否删除（逻辑删除）
     */
    @TableLogic
    private Boolean isDelete;

    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_FATHER_ID = "father_id";
    public static final String COL_ORDER_ID = "order_id";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_IS_DELETE = "is_delete";

}
