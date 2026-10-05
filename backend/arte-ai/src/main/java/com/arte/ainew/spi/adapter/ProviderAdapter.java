package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;
import com.arte.ainew.pojo.execution.GatewayCall;

import java.io.Serializable;
import java.util.List;

/**
 * 供应商能力与数据映射端口。
 *
 * <p>主要操作：声明或探测供应商支持的操作与特性，将平台类型化输入映射为供应商参数，
 * 将供应商输出、用量及错误转换为平台契约；不支持的特性明确拒绝或按已声明规则降级。
 *
 * <p>边界：负责供应商业务语义，不管理协议会话、连接池或受管进程，不解析凭据。
 * 序列化可委托给适用协议实现或 SDK，不要求预先生成完整请求字节；
 * 业务重试由执行协调层决定，不自行重试或重新派发调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:49 ✾
 **/
public interface ProviderAdapter<I extends CapabilityInput, Q, S, R extends Serializable> {

    /**
     * 稳定供应商标识；注册时与连接定义核对，不由客户端选择实现类。
     */
    String providerId();

    /**
     * 当前类型化适配实例的能力类别；注册键为 providerId＋kind，不从泛型擦除信息猜测。
     */
    CapabilityDescriptor.Kind kind();

    /**
     * 同一 kind 的不可变、有界、版本化能力声明；注册／发现不等于授权或远端探测成功。
     */
    List<CapabilityDescriptor> capabilities();

    /**
     * 纯映射，包含特性／参数兼容性检查；不能默默丢弃不支持的参数。
     * Q／S 是实现内部的协议或 SDK 类型，不进入持久化、业务 API 或日志。
     * R 为已登记的平台专有结果 DTO；一个适配实例处理一种类型化输入。
     */
    Q mapRequest(GatewayCall<I> call);

    /**
     * 将完整供应商响应转为平台结果；不虚构缺失用量、模型版本或来源权限。
     */
    R mapResult(S response, GatewayCall<I> call);

    /**
     * 仅返回脱敏错误事实；超时不能直接映射成“未执行”，retryable 不授权重试。
     */
    ExecutionError mapError(Throwable failure, GatewayCall<I> call);
}
