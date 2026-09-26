package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.WorkflowNodeRunPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工作流节点运行实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class WorkflowNodeRunDto extends WorkflowNodeRunPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -4591557279165321526L;
}

