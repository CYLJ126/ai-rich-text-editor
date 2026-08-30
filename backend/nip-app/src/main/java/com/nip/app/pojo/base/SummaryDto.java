package com.nip.app.pojo.base;

import com.baomidou.mybatisplus.annotation.TableField;
import com.nip.app.common.enums.SummaryOperationTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 总结内容表
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class SummaryDto extends SummaryPo implements Serializable {
    @Serial
    private static final long serialVersionUID = -7724360310690640789L;

    /**
     * 操作类型
     */
    @TableField(exist = false)
    private SummaryOperationTypeEnum operationType;
}
