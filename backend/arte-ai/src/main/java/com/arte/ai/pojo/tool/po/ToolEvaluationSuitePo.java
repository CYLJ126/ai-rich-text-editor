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
import java.util.List;
import java.util.Map;

/**
 * AI 工具评估套件实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_evaluation_suite", autoResultMap = true)
public class ToolEvaluationSuitePo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -3512355875412971145L;
    private String suiteId;
    private String version;
    private String name;
    private String description;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> evaluators;
    private String status;
    @Version
    private Long rowVersion;
}
