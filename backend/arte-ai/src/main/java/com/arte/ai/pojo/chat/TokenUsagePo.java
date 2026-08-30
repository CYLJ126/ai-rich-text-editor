package com.arte.ai.pojo.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.ai.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * Token 用量实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:45 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_ai_token_usage")
public class TokenUsagePo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -4848635718740487859L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    private LocalDate usageDate;
    private String userName;
    private Integer modelId;
    private String convId;
    private String messageId;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer reasoningTokens;
    private Integer totalTokens;
}