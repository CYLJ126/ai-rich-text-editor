package com.arte.app.pojo.base;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("arte_base_summary")
public class SummaryPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 7160495497684366041L;
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 对应类型
     */
    private String type;

    /**
     * 目标 ID
     */
    private Integer targetId;

    /**
     * 内容
     */
    private String content;

    public static final String COL_ID = "id";

    public static final String COL_TYPE = "type";

    public static final String COL_TARGET_ID = "target_id";

    public static final String COL_CONTENT = "content";
}
