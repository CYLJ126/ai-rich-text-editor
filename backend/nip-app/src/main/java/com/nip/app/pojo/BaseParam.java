package com.nip.app.pojo;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.nip.core.pojo.PageView;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 由于 {@link PaginationInnerInterceptor} 中返回结果时的 Page 包装器是入参，所以需要在此继承 PageView，返回数据库查询结果时，才能用 PageView 承接
 * <p>
 * Jackson 3.1.0 反序列化带泛型的参数时，需要在参数上注解 @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)，避免因原内容中没有 @class 属性，无法确定要解析为什么类，导致解析异常
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/10/3 0:15 ✾
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public class BaseParam<T> extends PageView<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = -1974913303781148610L;

    private Integer id;
    /**
     * 标签 ID 集合
     */
    private Set<Integer> tags;

    /**
     * 开始时间范围上限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startDateTimeCeil;

    /**
     * 开始时间范围下限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startDateTimeFloor;

    /**
     * 开始日期
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /**
     * 开始日期范围上限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDateCeil;

    /**
     * 开始日期范围下限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDateFloor;

    /**
     * 结束时间范围上限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endDateTimeCeil;

    /**
     * 结束时间范围下限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endDateTimeFloor;

    /**
     * 结束日期
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /**
     * 结束日期范围上限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDateCeil;

    /**
     * 结束日期范围下限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDateFloor;

    /**
     * 创建时间范围上限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTimeCeil;

    /**
     * 创建时间范围下限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTimeFloor;

    /**
     * 更新时间范围上限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTimeCeil;

    /**
     * 更新时间范围下限
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTimeFloor;

    /**
     * 创建人 id
     */
    private String createBy;

    /**
     * 更新人 id
     */
    private String updateBy;

    /**
     * elasticsearch 当前页参数，比数据库页码少 1，且为 int 类型
     *
     * @return 当前页参数
     */
    public Integer getEsCurrent() {
        int current = (int) this.getCurrent();
        return Math.max(current - 1, 0);
    }

    /**
     * elasticsearch 每页数量参数
     *
     * @return 每页数量参数
     */
    public Integer getEsSize() {
        return (int) this.getSize();
    }

    /**
     * 仅在 value 非空时写入 map，保持 filters 的干净
     */
    protected static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (Objects.nonNull(value)) {
            map.put(key, value);
        }
    }

    /**
     * 封装字数范围条件
     *
     * <p>使用 {@code Map<String, Integer>} 描述区间，
     * key 为 {@code gte} / {@code lte}，与 ES RangeQuery 语义对齐。</p>
     */
    protected static void buildRange(Map<String, Object> filters, String key, Integer floor, Integer ceil) {
        if (floor == null && ceil == null) {
            return;
        }
        Map<String, Integer> range = new HashMap<>(2);
        if (floor != null) {
            range.put("gte", floor);
        }
        if (ceil != null) {
            range.put("lte", ceil);
        }
        filters.put(key, range);
    }
}
