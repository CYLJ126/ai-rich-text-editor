package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.util.Map;

/**
 * AI 助手工具关联实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_assistant_tool", autoResultMap = true)
public class AssistantToolPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 76944937052985843L;
    private Integer assistantId;
    private String bindingId;
    private Boolean enabled;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> policyOverride;
    private Integer sortOrder;
}
