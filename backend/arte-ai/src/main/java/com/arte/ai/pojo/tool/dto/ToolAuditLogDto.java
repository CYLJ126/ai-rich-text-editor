package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolAuditLogPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具审计日志实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolAuditLogDto extends ToolAuditLogPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 4991557747189760232L;
}

