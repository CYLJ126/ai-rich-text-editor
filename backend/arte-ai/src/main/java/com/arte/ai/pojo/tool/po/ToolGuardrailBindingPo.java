package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * AI 工具 Guardrail 绑定实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("arte_ai_tool_guardrail_binding")
public class ToolGuardrailBindingPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 6141639495673979807L;
    private String guardrailId;
    private String scopeType;
    private String scopeId;
    private Integer priority;
    private String failMode;
    private Boolean enabled;
}
