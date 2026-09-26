package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolCredentialPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具凭据元数据实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolCredentialDto extends ToolCredentialPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 5449671863330043367L;
}

