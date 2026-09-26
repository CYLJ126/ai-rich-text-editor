package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolBindingPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具用户绑定实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolBindingDto extends ToolBindingPo implements Serializable {
    @Serial
    private static final long serialVersionUID = -4221997586796316353L;
}

