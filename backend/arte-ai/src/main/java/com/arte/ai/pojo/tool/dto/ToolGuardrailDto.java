package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolGuardrailPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具 Guardrail 实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolGuardrailDto extends ToolGuardrailPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 8364684911258472428L;
}

