package com.arte.ai.pojo.message;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * AI 消息原始请求/响应日志实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 22:04 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class MessageRawLogDto extends MessageRawLogPo {
    @Serial
    private static final long serialVersionUID = 2379525770464742481L;
}
