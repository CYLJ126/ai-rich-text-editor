package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 工具产物实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_artifact", autoResultMap = true)
public class ToolArtifactPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -8841266223146177262L;
    private String artifactId;
    private String callId;
    private String taskId;
    private String workflowRunId;
    private String name;
    private String mediaType;
    private String uri;
    private Long sizeBytes;
    private String checksum;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
    private LocalDateTime expiresAt;
}
