package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具目录实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolDto extends ToolPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 3326042004031903426L;
}

