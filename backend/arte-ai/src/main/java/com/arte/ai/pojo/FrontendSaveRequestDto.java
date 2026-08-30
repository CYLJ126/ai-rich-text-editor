package com.arte.ai.pojo;

import com.arte.ai.pojo.message.SaveMessageDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

import java.io.Serial;
import java.io.Serializable;

/**
 * 前端直连模式：前端保存请求/响应数据到后端
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:48 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class FrontendSaveRequestDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -2201245930371776179L;
    /**
     * 会话ID
     */
    @NotBlank(message = "会话ID不能为空")
    private String convId;
    /**
     * 用户消息
     */
    private SaveMessageDto userMessage;
    /**
     * AI消息
     */
    private SaveMessageDto assistantMessage;
    /**
     * 当前操作用户名
     */
    private String userName;
}
