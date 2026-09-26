package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolGuardrailBindingPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具 Guardrail 绑定实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolGuardrailBindingDto extends ToolGuardrailBindingPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1052307730365286001L;
}

