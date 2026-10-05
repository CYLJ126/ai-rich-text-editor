package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.execution.GatewayCall;
import reactor.core.publisher.Flux;

/**
 * 外部协议语义适配端口。
 *
 * <p>主要操作：处理协议握手、能力发现、消息编码与解码、请求关联、认证交互，
 * 以及协议会话、远端任务与取消语义；声明协议版本、传输方式及支持矩阵。
 *
 * <p>边界：负责协议状态与消息规则，底层网络连接、受管进程及凭据解析委托给连接运行时；
 * 供应商专有参数和结果含义由供应商适配器映射，协议能力不替代业务授权。
 * 仅执行已允许的协议恢复，不因重连隐式重放业务操作，不自行决定业务重试。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:49 ✾
 **/
public interface ProtocolAdapter<Q, S, H> {

    /** 固定协议实现版本，用于与连接配置核对；协议恢复不许可业务重放。 */
    DefinitionRef definition();

    /**
     * 一次请求交换，负责编码／解码、关联及适用的握手；H 为受控内部传输句柄。
     * 连接和凭据由 runtime 管理；有界背压、取消、期限、响应大小限制贯穿交换。
     * Q／S 可为 SDK 类型，不要求先物化完整正文；不自行决定重试。
     */
    Flux<S> exchange(Q request, ConnectionRuntime.Lease<H> connection, GatewayCall<?> call);
}
