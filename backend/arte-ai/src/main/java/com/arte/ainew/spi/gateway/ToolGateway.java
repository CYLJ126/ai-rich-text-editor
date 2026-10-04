package com.arte.ainew.spi.gateway;

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
}
