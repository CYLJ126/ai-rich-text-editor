package com.arte.core.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 绝对时间点的左闭右开查询范围，可用于创建、更新或业务开始、结束时间。
 * <p>
 * JSON 使用 ISO-8601 字符串，例如 {@code 2026-10-07T00:00:00+08:00} 或
 * {@code 2026-10-06T16:00:00Z}；表示同一时间点的偏移会统一为 UTC，保留小数秒精度。
 * <p>
 * 业务查询显式应用 {@code >= from}、{@code < to}。数据库列若保存无时区的本地时间，
 * 持久化适配层须按明确配置的时区转换，不依赖 JVM 默认时区。
 * 引用该组件的请求字段应添加 {@code @Valid} 以启用范围校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 13:35 ✾
 */
@Getter
@Setter
@ToString
public class InstantRangeParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 包含起点；null 表示不限制起点。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant from;

    /**
     * 不包含终点；null 表示不限制终点。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant to;

    /**
     * 单侧及无边界范围有效；相同时间点表示空区间。
     */
    @JsonIgnore
    @AssertTrue(message = "时间范围起点不能晚于终点")
    public boolean isRangeValid() {
        return from == null || to == null || !from.isAfter(to);
    }
}
