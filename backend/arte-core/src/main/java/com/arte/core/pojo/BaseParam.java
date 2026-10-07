package com.arte.core.pojo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 实体列表、实体详情查询的公共条件，业务查询参数可继承或组合使用。
 * <p>
 * 只描述客户端选择的分页和筛选条件，不承载记录、总数或响应状态。
 * 列表及详情查询可按业务需要继承或组合使用；创建、更新请求独立定义。
 * 业务字段和关键词对应的查询列由业务层决定。
 * <p>
 * 数据库查询时将 {@link PageParam} 转换为独立分页对象，结果通过
 * {@link PageView} 包装；普通结果通过 {@link ResultContext} 包装。
 * <p>
 * 时间范围统一采用左闭右开区间：业务查询使用 {@code >= from} 和 {@code < to}，
 * null 表示不限制对应边界。创建、更新时间使用绝对时间点，JSON 采用带时区偏移的 ISO-8601 字符串。
 * <p>
 * 不声明创建人作为通用查询条件。
 * 数据归属与访问范围由后端依据当前认证和授权信息确定，不作为客户端可指定的通用查询条件。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 13:35 ✾
 */
@Getter
@Setter
@ToString
public class BaseParam implements Serializable {

    @Serial
    private static final long serialVersionUID = -4621592577621711857L;

    /**
     * 分页对象省略时使用默认条件；显式 null 或非法分页值拒绝。
     */
    @Valid
    @NotNull
    private PageParam page = new PageParam();

    /**
     * 数据库表自增主键的查询条件；null 表示不按主键筛选，由业务查询显式应用。
     */
    private Long id;

    /**
     * 关键词是否启用及匹配哪些列，由业务查询显式处理。
     */
    @Size(max = 200)
    private String keyword;

    /**
     * 创建时间范围；省略或 null 表示不按创建时间筛选。
     */
    @Valid
    private InstantRangeParam createTime;

    /**
     * 更新时间范围；省略或 null 表示不按更新时间筛选。
     */
    @Valid
    private InstantRangeParam updateTime;

    /**
     * 开始时间范围；省略或 null 表示不按开始时间筛选。
     */
    @Valid
    private InstantRangeParam startTime;

    /**
     * 结束时间范围；省略或 null 表示不按结束时间筛选。
     */
    @Valid
    private InstantRangeParam endTime;

    /**
     * 开始日期范围；省略或 null 表示不按开始日期筛选。
     */
    @Valid
    private DateRangeParam startDate;

    /**
     * 结束日期范围；省略或 null 表示不按结束日期筛选。
     */
    @Valid
    private DateRangeParam endDate;
}
