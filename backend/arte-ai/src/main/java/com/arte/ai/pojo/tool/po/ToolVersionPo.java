package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具版本实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_version", autoResultMap = true)
public class ToolVersionPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -4802735965653023008L;
    private String toolId;
    private String version;
    private String title;
    private String description;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputSchema;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> outputSchema;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> capabilities;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> riskProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> defaultConfiguration;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> defaultPolicy;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<String> tags;
    private String checksum;
    private ToolLifecycleStateEnum lifecycleState;
    private LocalDateTime publishedAt;
    @Version
    private Long rowVersion;
}
