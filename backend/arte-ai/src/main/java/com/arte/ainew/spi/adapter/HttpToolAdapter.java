package com.arte.ainew.spi.adapter;

/**
 * 受控 HTTP 工具的协议标记；与 MCP 并列，共用远端调用契约。
 * 受信代码将实例登记到 HTTP 槽，注册及发布预检仍核对固定 HTTP 工具版本与连接。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public interface HttpToolAdapter extends RemoteToolAdapter {
}
