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
 * AI 工具评估用例实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_evaluation_case", autoResultMap = true)
public class ToolEvaluationCasePo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 8842900760362308370L;
    private String caseId;
    private String suiteId;
    private String suiteVersion;
    private String name;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> toolCall;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object expectedOutcome;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> criteria;
    private Boolean enabled;
    private Integer sortOrder;
}
