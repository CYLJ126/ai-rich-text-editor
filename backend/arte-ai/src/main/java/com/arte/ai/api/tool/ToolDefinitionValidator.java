package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolDefinition;

/**
 * 工具定义校验器，在工具注册、同步和发布前执行同一套校验规则。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public interface ToolDefinitionValidator {

    void validate(ToolDefinition definition);
}
