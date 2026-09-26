package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.WorkflowCheckpointPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工作流检查点实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class WorkflowCheckpointDto extends WorkflowCheckpointPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -8695289605510967102L;
}

