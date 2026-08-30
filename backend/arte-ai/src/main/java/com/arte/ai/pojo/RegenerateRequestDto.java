package com.arte.ai.pojo;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息重新生成请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:49 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class RegenerateRequestDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -5783644277155122342L;
    /**
     * 要重新生成的AI消息ID
     */
    @NotBlank(message = "消息ID不能为空")
    private String messageId;
    /**
     * 当前操作用户名
     */
    private String userName;
}
