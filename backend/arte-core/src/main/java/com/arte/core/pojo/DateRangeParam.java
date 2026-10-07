package com.arte.core.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 业务日期的左闭右开查询范围，例如开始日期、结束日期或有效期。
 * <p>
 * JSON 使用 {@code yyyy-MM-dd}；业务查询显式应用 {@code >= from}、{@code < to}。
 * 日期本身不带时区；筛选时间点列时，由业务层明确时区后转换为当天起点。
 * 引用该组件的请求字段应添加 {@code @Valid} 以启用范围校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 13:35 ✾
 */
@Getter
@Setter
@ToString
public class DateRangeParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 包含起点；null 表示不限制起点。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate from;

    /**
     * 不包含终点；null 表示不限制终点。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate to;

    /**
     * 单侧及无边界范围有效；相同日期表示空区间。
     */
    @JsonIgnore
    @AssertTrue(message = "日期范围起点不能晚于终点")
    public boolean isRangeValid() {
        return from == null || to == null || !from.isAfter(to);
    }
}
