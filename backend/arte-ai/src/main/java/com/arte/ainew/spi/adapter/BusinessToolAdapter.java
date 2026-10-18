package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.LocalToolCall;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.pojo.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 业务工具适配器服务
 * <p>
 * 主要操作：将领域查询／命令暴露为受限工具；由组合模块调用实际领域端口，保留权限、版本及事务规则，不直接修改领域数据库。
 * 边界：AI 定义工具接入契约，组合模块调用实际领域端口，不直接改库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:50 ✾
 **/
public interface BusinessToolAdapter {

    /**
     * 固定版本的工具能力声明；只读取有界元数据，不执行领域操作或外部探测。
     * 声明不授予发布资格，副作用、Schema 和当前权限仍由控制面、网关及领域校验。
     */
    CapabilityDescriptor capability();

    /**
     * 委托实际领域 Query／Command；保留领域事务、版本冲突及领域幂等，不直接改领域数据库。
     */
    Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call);

    /**
     * 接收网关核实的固定配置及当前授权；需要配置的适配器应覆盖此方法，在领域边界继续检查资源权限。
     * 兼容旧端口仅限空配置，非空配置明确拒绝，不能悄悄忽略用户设置。
     */
    default Mono<ToolResult> invoke(LocalToolCall call) {
        if (call.binding().effectiveConfiguration().fields().isEmpty()) {
            return invoke(call.call());
        }
        var error = new ExecutionError("TOOL_CONFIGURATION_UNSUPPORTED", ExecutionError.Phase.DISPATCH, false,
                ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, call.call().request().context().traceId());
        return Mono.just(new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.FAILED,
                null, List.of(), List.of(), error.sideEffect(), error));
    }
}
