package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具 Guardrail 实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_guardrail", autoResultMap = true)
public class ToolGuardrailPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 8853803418576370923L;
    private String guardrailId;
    private String ownerId;
    private String name;
    private String implementation;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<GuardrailPhaseEnum> phases;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configuration;
    private Integer priority;
    private String failMode;
    private Boolean enabled;
    @Version
    private Long rowVersion;
}
