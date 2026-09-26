package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具审批实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolApprovalDto extends ToolApprovalPo implements Serializable {
    @Serial
    private static final long serialVersionUID = -3704414842139742112L;
}

