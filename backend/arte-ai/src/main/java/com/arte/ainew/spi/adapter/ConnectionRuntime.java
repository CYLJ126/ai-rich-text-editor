package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import reactor.core.publisher.Mono;

/**
 * 连接与运行资源管理端口，供连接实现扩展。
 *
 * <p>主要操作：建立、复用和释放网络连接或受管进程，管理连接池与传输资源，
 * 在发送时按 SecretRef 解析并受控注入凭据，支持连接停用、故障失效与资源清理。
 *
 * <p>边界：负责底层传输资源，不解释供应商业务参数，不推进协议会话或远端任务状态，
 * 不自行重试业务操作。按连接、租户及凭据等适用隔离边界复用资源，执行网络出口约束；
 * 限制连接数、缓冲、单消息及累计响应大小，并落实传输超时，不向普通结果或日志暴露凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:47 ✾
 **/
public interface ConnectionRuntime<H> {

    /**
     * 按固定连接、主体隔离和当前凭据版本获取资源；执行停用、出口与凭据授权检查。
     */
    Mono<Lease<H>> acquire(ConnectionDefinition definition, ExecutionRuntimeContext runtime);

    /**
     * 每个领取句柄幂等释放；正常结束、异常和取消均须释放，可由 usingWhen 组合。
     */
    Mono<Void> release(Lease<H> lease);

    /**
     * 受信配置变更／故障时失效连接资源；不得因此自动重放已发送业务。
     */
    Mono<Void> invalidate(DefinitionRef connection, ExecutionContext context);

    /**
     * 实例内资源借用凭证，禁止序列化或从 HTTP 构造；handle 不进入普通结果或日志。
     */
    interface Lease<H> {
        DefinitionRef connection();

        ExecutionOwner owner();

        H handle();
    }
}
