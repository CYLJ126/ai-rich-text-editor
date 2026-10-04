package com.arte.ainew.spi.adapter;

/**
 * 资源上下文适配器服务
 * 组合模块提供已授权的固定资料，根据选定引用重新授权并读取实际内容；AI 核心不直接访问业务数据库。
 * <p>
 * 主要操作：按授权资源／范围输出内容及来源等。
 * 边界：AI 定义资源提供端口，组合模块提供文章或其他领域实现。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:45 ✾
 **/
public interface ResourceContextAdapter {
}
