package com.arte.app.platform.ai.tool;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingResolution;

/**
 * 宿主完整契约白名单与领域资格；免费声明不自动授权，资源复核不能执行业务变更。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:48 ✾
 */
public interface LocalToolPolicy {

    void requireFree(ToolAdmissionInput input, ToolBindingResolution binding);

    default void requireResource(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
    }
}
