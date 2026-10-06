package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.execution.GatewayCall;
import reactor.core.publisher.Flux;

/**
 * 外部协议语义适配端口。
 * <p>
 * 主要操作：处理协议握手、能力发现、消息编码与解码、请求关联、认证交互，以及协议会话、远端任务与取消语义；声明协议版本、传输方式及支持矩阵。
 * <p>
 * 边界：负责协议状态与消息规则，底层网络连接、受管进程及凭据解析委托给 ConnectionRuntime；
 * 供应商专有参数和结果含义由 ProviderAdapter 映射，协议能力不替代业务授权。
 * 仅执行已允许的协议恢复，不因重连隐式重放业务操作，不自行决定业务重试。
 * <p>
 * 泛型用于连接供应商数据映射与具体传输实现，避免公共端口固定依赖某个协议 DTO 或 SDK：
 * <ol>
 *     <li>Q 是供应商适配器 mapRequest 生成的内部请求类型，由本接口编码并发送；</li>
 *     <li>S 是协议解码后返回的单个响应元素类型，由供应商适配器继续映射为平台结果或生成信号；</li>
 *     <li>Flux 中的一个 S 可以是响应帧、增量或结束标记，不一定是完整响应，也不是平台耐久事件；</li>
 *     <li>H 是 ConnectionRuntime 提供的内部传输句柄类型，通过 ConnectionRuntime.Lease 获取并在借用期间使用，
 *     可封装受控 HTTP 客户端、SDK 客户端或协议会话所需的传输资源；生命周期由 ConnectionRuntime 管理管理。</li>
 * </ol>
 * 同一调用链中，Q／S 应与 ProviderAdapter 的请求／响应类型一致，H 应与 ConnectionRuntime 的句柄类型一致。
 * 例如 DeepSeek SSE 实现使用 {@code ProtocolAdapter<DeepSeekWire.Request, SseFrame, HttpConnectionRuntime.Handle>}：
 * 请求 DTO 编码为 HTTP 正文，响应解码为 SSE 数据帧或结束标记，HTTP 句柄提供受控传输能力。
 * 这些泛型属于实现内部边界，不要求实现 Serializable，不进入业务 API、持久化快照或普通日志。
 *
 * @param <Q> 协议交换的内部请求类型，可以是协议 DTO 或 SDK 请求对象
 * @param <S> 协议解码后的响应元素类型，可以是完整响应、增量帧或结束标记
 * @param <H> ConnectionRuntime 管理的内部传输句柄类型，通过 Lease 受控借用
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:49 ✾
 **/
public interface ProtocolAdapter<Q, S, H> {

    /**
     * 固定协议实现版本，用于与连接配置核对；协议恢复不许可业务重放。
     */
    DefinitionRef definition();

    /**
     * 一次请求交换，负责编码／解码、关联及适用的握手；H 为受控内部传输句柄。
     * 连接和凭据由 runtime 管理；有界背压、取消、期限、响应大小限制贯穿交换。
     * Q／S 可为 SDK 类型，不要求先物化完整正文；不自行决定重试。
     */
    Flux<S> exchange(Q request, ConnectionRuntime.Lease<H> connection, GatewayCall<?> call);
}
