package com.nip.app.pojo.dw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.nip.app.common.enums.DwStickyShowTypeEnum;
import com.nip.app.pojo.BaseDto;
import com.nip.core.enums.StatusEnum;
import com.nip.core.enums.YesOrNoEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * <p>
 * 便笺表
 * </p>
 *
 * @author zhangsc
 * @since 2025-06-20
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("nip_dw_sticky")
public class StickyPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 509222848685470321L;

    /**
     * 便笺id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 标题
     */
    private String title;

    /**
     * 顺序
     */
    private Integer orderId;

    /**
     * 折叠标记：0-折叠；1-展开；
     */
    private YesOrNoEnum foldFlag;

    /**
     * 表现形式
     */
    private DwStickyShowTypeEnum showType;

    /**
     * 便笺状态
     */
    private StatusEnum status;

    /**
     * 宽度
     */
    private Integer width;

    /**
     * 高度
     */
    private Integer height;

    /**
     * 内容
     */
    private String content;

    /**
     * 标签主题色，默认为 81d3f8
     */
    private String themeColor;

    /**
     * 开始日期
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /**
     * 结束日期
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    public static final String COL_ID = "id";

    public static final String COL_TITLE = "title";

    public static final String COL_ORDER_ID = "order_id";

    public static final String COL_FOLD_FLAG = "fold_Flag";

    public static final String COL_SHOW_TYPE = "show_type";

    public static final String COL_STATUS = "status";

    public static final String COL_WIDTH = "width";

    public static final String COL_HEIGHT = "height";

    public static final String COL_CONTENT = "content";

    public static final String COL_THEME_COLOR = "theme_color";

    public static final String COL_CREATE_BY = "create_by";

    public static final String COL_UPDATE_BY = "update_by";

    public static final String COL_START_DATE = "start_date";

    public static final String COL_END_DATE = "end_date";

    public static final String COL_CREATE_TIME = "create_time";

    public static final String COL_UPDATE_TIME = "update_time";
}
