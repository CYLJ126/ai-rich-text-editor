package com.arte.ainew.spi.adapter;

/**
 * 受管 MCP 工具的协议标记；与 HTTP 并列，共用远端调用契约。
 * 实例须独立登记到 MCP 槽，并匹配固定 MCP 协议、连接与工具版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public interface McpToolAdapter extends RemoteToolAdapter {
}
