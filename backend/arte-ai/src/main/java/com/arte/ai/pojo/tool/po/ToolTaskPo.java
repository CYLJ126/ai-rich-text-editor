package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 工具延迟任务实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_task", autoResultMap = true)
public class ToolTaskPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 3528135083644111499L;
    private String taskId;
    private String callId;
    private String status;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> argumentsSnapshot;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> executionPolicy;
    private String ownerId;
    private String subjectId;
    private String traceId;
    private String idempotencyKey;
    private String credentialBindingId;
    private BigDecimal progress;
    private String progressMessage;
    private String resumeTokenHash;
    private Integer attempt;
    private LocalDateTime nextAttemptAt;
    private String workerId;
    private LocalDateTime leaseUntil;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
    @Version
    private Long rowVersion;
}
