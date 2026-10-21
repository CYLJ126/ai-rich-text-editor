package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingResolution;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;

/**
 * 显式登记的收费实现，必须提供完整固定合同与当前资源授权；账单通过 ToolResult.charge 返回。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public interface PaidToolAdapter extends BusinessToolAdapter {

    ToolDefinitionRevision revision();

    void requireCharge(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding);
}
