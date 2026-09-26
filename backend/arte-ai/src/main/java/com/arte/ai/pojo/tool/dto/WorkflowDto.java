package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.WorkflowPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工作流目录实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class WorkflowDto extends WorkflowPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -9096138385704613428L;
}

