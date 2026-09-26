package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolTaskPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具延迟任务实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolTaskDto extends ToolTaskPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 7489648452158688632L;
}

