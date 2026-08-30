package com.arte.core.utils.mytime;

import com.arte.core.enums.MyTimeUnit;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 周信息参数
 *
 * @author zhangsc
 * @since 2025/12/16 11:37
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@NoArgsConstructor
public class MyTimeInfoDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 5367429366475417954L;

    private MyTimeUnit unit;
    /**
     * 如 2025 年第 45 周，则为 2545
     * 如 2026 年第 1 周，则为 2601
     */
    private Integer value;
    /**
     * 如 “第 2045 周”
     */
    private String label;
    /**
     * 指定周内的某个时间点
     */
    private LocalDateTime time;

    public MyTimeInfoDto(MyTimeUnit unit, Integer value, LocalDateTime time) {
        this.unit = unit;
        this.value = value;
        this.time = time;
        formatLabel();
    }

    public void formatLabel() {
        this.label = String.format(unit.getFormat(), value);
    }
}
