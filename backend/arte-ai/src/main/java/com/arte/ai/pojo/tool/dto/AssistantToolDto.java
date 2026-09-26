package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.AssistantToolPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 助手工具关联实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class AssistantToolDto extends AssistantToolPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1115899952265637283L;
}

