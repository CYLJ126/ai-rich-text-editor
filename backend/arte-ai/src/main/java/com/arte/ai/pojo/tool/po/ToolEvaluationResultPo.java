package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * AI 工具评估结果实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_evaluation_result", autoResultMap = true)
public class ToolEvaluationResultPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 4320940320504583298L;
    private String resultId;
    private String runId;
    private String suiteId;
    private String caseId;
    private String evaluatorName;
    private String callId;
    private String traceId;
    private Boolean passed;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Double> scores;
    private String feedback;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> evidence;
}
