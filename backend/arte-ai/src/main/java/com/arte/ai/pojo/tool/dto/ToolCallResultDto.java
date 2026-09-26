package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolCallResultPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具调用结果实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolCallResultDto extends ToolCallResultPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 8645695812267076452L;
}

