package com.arte.ainew.spi.adapter;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.RemoteToolCall;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.pojo.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 受控远端工具的共用执行契约；HTTP 与 MCP 分别声明协议身份并独立登记。
 * 固定版本元数据不触发外联；执行须接收网关复核后的绑定与当前授权，不自行重选工具版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 20:08 ✾
 */
public interface RemoteToolAdapter extends BusinessToolAdapter {

    ToolDefinitionRevision revision();

    @Override
    default CapabilityDescriptor capability() {
        return revision().tool().discoveredCapability();
    }

    Mono<ToolResult> invoke(RemoteToolCall call);

    /**
     * 旧入口缺少已核实的远端固定绑定，明确拒绝，避免绕过配置及远端授权边界。
     */
    @Override
    default Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
        return Mono.error(new IllegalArgumentException("Remote tool requires verified fixed binding"));
    }
}
