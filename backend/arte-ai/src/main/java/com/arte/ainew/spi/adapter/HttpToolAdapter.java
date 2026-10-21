package com.arte.ainew.spi.adapter;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.RemoteToolCall;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.pojo.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 规定受控 HTTP 工具如何声明固定契约、接收调用并返回结果。
 * <p>
 * 受信代码登记的固定 HTTP 只读契约；发现阶段只读取元数据，不执行网络探测。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public interface HttpToolAdapter extends BusinessToolAdapter {

    ToolDefinitionRevision revision();

    default CapabilityDescriptor capability() {
        return revision().tool().discoveredCapability();
    }

    Mono<ToolResult> invoke(RemoteToolCall call);

    default Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
        return Mono.error(new IllegalArgumentException("HTTP tool requires verified fixed binding"));
    }
}
