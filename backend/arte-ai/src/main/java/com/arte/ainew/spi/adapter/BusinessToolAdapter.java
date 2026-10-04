package com.arte.ainew.spi.adapter;

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
}
