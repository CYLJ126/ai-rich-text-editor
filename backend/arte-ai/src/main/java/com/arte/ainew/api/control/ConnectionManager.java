package com.arte.ainew.api.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

/**
 * 为模型操作解析可信连接定义。
 * <p>
 * 连接定义包含版本化配置和凭据引用，不包含明文凭据。
 * 必须保留授权及连接状态检查；传输层校验仍受网络出口策略约束。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:33 ✾
 */
public interface ConnectionManager {

    /**
     * 解析当前执行上下文可使用的版本化连接。
     * 实现可复用本次操作的准备快照。
     *
     * @param connection 可信连接定义引用
     * @param context 执行身份、权限上限和截止时间
     * @return 已授权的连接定义，或访问、配置错误
     */
    Mono<ConnectionDefinition> resolve(DefinitionRef connection, ExecutionContext context);

    /**
     * 在外部请求真正发送前的最终边界重新核验连接。
     * <p>
     * 复用操作快照的实现必须覆盖此方法，重新检查当前资格和配置。
     * 默认委托给 {@link #resolve}，适用于解析时已经执行当前状态检查的实现。
     *
     * @param connection 准备阶段选定的连接引用
     * @param context    需要重新核验的执行身份、权限上限和截止时间
     * @return 当前已授权的连接定义，或访问、配置错误
     */
    default Mono<ConnectionDefinition> resolveCurrent(DefinitionRef connection, ExecutionContext context) {
        return resolve(connection, context);
    }

    /**
     * 在发送前针对本次具体模型调用重新核验连接。
     * <p>
     * 实现可进一步核验本次调用选定的模型绑定和预算配置。
     * 默认使用调用的运行时上下文执行当前资格检查。
     *
     * @param connection 本次调用选定的连接引用
     * @param call       可信服务端调用，包含请求、已解析绑定和运行时上下文
     * @return 当前已授权的连接定义，或调用、配置错误
     */
    default Mono<ConnectionDefinition> resolveCurrent(DefinitionRef connection,
                                                      com.arte.ainew.pojo.execution.GatewayCall<?> call) {
        return resolveCurrent(connection, call.runtime().execution());
    }
}
