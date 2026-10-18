package com.arte.ainew.spi.gateway;

import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.pojo.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 工具网关
 * <p>
 * 主要操作：invoke、返回 ToolResult
 * 边界：重新检查绑定、参数、资源权限及副作用
 * <p>
 * 工具的参数、资源权限及副作用检查由 ToolGateway 落实，通用准入与预算仍经过统一执行链路。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:00 ✾
 **/
public interface ToolGateway {

    /**
     * 重新校验绑定、工具 Schema、权限及副作用；一次调用，不自行执行模型后续决策或重试。
     * 当前本地实现只开放免收费只读工具；需审批、网络、Secret 或付费计划的调用明确拒绝。
     * 返回的 Publisher 只允许订阅一次；调用方仍须维护耐久派发、租约和最终结果提交。
     * 进入适配器后的异常／超时不能当作未执行证据，未知结果须交给执行协调层保存及核对。
     */
    Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call);
}
