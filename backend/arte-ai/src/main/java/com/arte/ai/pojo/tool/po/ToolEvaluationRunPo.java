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
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 工具评估运行实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_evaluation_run", autoResultMap = true)
public class ToolEvaluationRunPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -3956356944416355560L;
    private String runId;
    private String suiteId;
    private String suiteVersion;
    private String status;
    private Integer totalCases;
    private Integer completedCases;
    private Integer passedCases;
    private Integer failedCases;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configuration;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    @Version
    private Long rowVersion;
}
