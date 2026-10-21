package com.arte.ainew.pojo.tool;

import com.arte.ainew.pojo.execution.GatewayCall;

/**
 * 网关完成固定材料及当前授权检查后的单次调用；不代表已获网络权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public sealed interface ToolExecutionCall permits LocalToolCall, RemoteToolCall {

    GatewayCall<ToolInvocation> call();

    ToolBindingResolution binding();
}
