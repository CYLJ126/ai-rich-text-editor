package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 工具提供者实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_provider", autoResultMap = true)
public class ToolProviderPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 6464090637552358481L;
    private String providerId;
    private String name;
    private String providerType;
    private String endpoint;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;
    private String credentialReference;
    private String status;
    private LocalDateTime lastSyncTime;
    private String lastError;
}
