package com.nip.ai.pojo.chat;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * Token 用量实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:46 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class TokenUsageDto extends TokenUsagePo implements Serializable {
    @Serial
    private static final long serialVersionUID = 2511248274863361281L;
    /**
     * 聚合查询参数
     */
    @TableField(exist = false)
    private LocalDate startDate;
    @TableField(exist = false)
    private LocalDate endDate;
    /**
     * 聚合结果：总prompt tokens
     */
    @TableField(exist = false)
    private Long totalPromptTokens;
    /**
     * 聚合结果：总completion tokens
     */
    @TableField(exist = false)
    private Long totalCompletionTokens;
    /**
     * 聚合结果：总think tokens
     */
    @TableField(exist = false)
    private Long totalThinkTokens;
}
