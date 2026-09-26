package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolEvaluationResultPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具评估结果实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolEvaluationResultDto extends ToolEvaluationResultPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -6327636598514920386L;
}

