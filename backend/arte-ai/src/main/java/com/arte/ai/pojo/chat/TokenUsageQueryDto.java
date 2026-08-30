package com.arte.ai.pojo.chat;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * Token用量统计查询请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:51 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class TokenUsageQueryDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -21572260698334727L;
    private String startDate;
    private String endDate;
    private String modelId;
    private String convId;
    /**
     * 聚合维度：day/model/conv
     */
    private String groupBy;
}
