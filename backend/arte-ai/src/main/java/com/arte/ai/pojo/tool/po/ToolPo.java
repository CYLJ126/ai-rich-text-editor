package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * AI 工具目录实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("arte_ai_tool")
public class ToolPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 5224272517616653103L;
    private String toolId;
    private String namespace;
    private String name;
    private String providerId;
    private String title;
    private String description;
    private String latestVersion;
    private ToolLifecycleStateEnum lifecycleState;
}
