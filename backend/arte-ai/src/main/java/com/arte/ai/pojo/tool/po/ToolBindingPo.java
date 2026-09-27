package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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

/**
 * AI 工具用户绑定实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_binding", autoResultMap = true)
public class ToolBindingPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 2916318977187672170L;
    private String bindingId;
    private String ownerId;
    private String workspaceId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String workspaceScope;
    private String toolId;
    private String toolVersion;
    private String credentialReference;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configuration;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> policyOverride;
    private Boolean enabled;
    @Version
    private Long rowVersion;
}
