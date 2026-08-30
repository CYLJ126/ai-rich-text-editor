package com.nip.ai.pojo;

import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 编辑消息并重发请求 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:24 ✾
 **/
@Data
@Accessors(chain = true)
public class EditMessageRequestDto {

    /**
     * 要编辑的用户消息 ID
     */
    @NotBlank(message = "消息ID不能为空")
    private String messageId;

    /**
     * 编辑后的新内容
     */
    @NotBlank(message = "新内容不能为空")
    private String newContent;
    /**
     * 模型ID
     */
    private String modelId;
    /**
     * 模型参数
     */
    private Map<String, Object> modelParams;
    /**
     * 当前操作用户名
     */
    private String userName;
}
