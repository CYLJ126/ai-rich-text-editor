package com.nip.app.pojo.base;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.pojo.BaseDto;
import com.nip.core.enums.StatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

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
@TableName("nip_base_tag")
@Accessors(chain = true)
public class TagPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 678614378088490108L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 标签名
     */
    private String name;

    /**
     * 顺序
     */
    private Integer orderId;

    /**
     * 状态
     */
    private StatusEnum status;

    /**
     * 描述
     */
    private String description;

    /**
     * 父id，无则为顶级标签
     */
    private Integer fatherId;

    public static final String COL_ID = "id";

    public static final String COL_NAME = "name";

    public static final String COL_ORDER_ID = "order_id";

    public static final String COL_STATUS = "status";

    public static final String COL_DESCRIPTION = "description";

    public static final String COL_FATHER_ID = "father_id";
}
