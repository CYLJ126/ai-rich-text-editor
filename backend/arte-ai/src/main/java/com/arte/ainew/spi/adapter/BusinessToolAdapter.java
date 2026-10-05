package com.arte.ainew.spi.adapter;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.pojo.tool.ToolResult;
import reactor.core.publisher.Mono;

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

    /** 固定版本的工具能力声明；副作用、Schema 和当前权限仍由网关及领域校验。 */
    CapabilityDescriptor capability();

    /** 委托实际领域 Query／Command；保留领域事务、版本冲突及领域幂等，不直接改领域数据库。 */
    Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call);
}
