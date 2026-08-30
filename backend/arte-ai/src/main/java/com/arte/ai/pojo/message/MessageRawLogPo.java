package com.arte.ai.pojo.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.ai.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.ibatis.type.JdbcType;

import java.io.Serial;

/**
 * AI 消息原始请求/响应日志表
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 22:04 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_message_raw_log", autoResultMap = true)
public class MessageRawLogPo extends BaseDto {

    @Serial
    private static final long serialVersionUID = -5268095613809138028L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String convId;
    private String messageId;
    private String provider;
    private String modelId;
    private Integer statusCode;

    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String requestBody;

    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String responseBody;
}
