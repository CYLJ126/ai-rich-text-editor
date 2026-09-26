package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolProviderPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具提供者实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolProviderDto extends ToolProviderPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 2833499133735476645L;
}

