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
import java.util.Set;

/**
 * AI 工具访问策略实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_access_policy", autoResultMap = true)
public class ToolAccessPolicyPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 5577342899933267279L;
    private String policyId;
    private String ownerId;
    private String subjectType;
    private String subjectId;
    private String resourceType;
    private String resourceId;
    private String effect;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<String> requiredScopes;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> conditions;
    private Integer priority;
    private Boolean enabled;
}
