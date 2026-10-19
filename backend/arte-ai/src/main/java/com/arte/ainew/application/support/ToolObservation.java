package com.arte.ainew.application.support;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.core.diagnostics.OperationObservation;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * AI TOOL 调用的阶段观测入口
 * <p>
 * 定义工具阶段，提取 ExecutionContext 中的调用 ID、追踪 ID 和期限。
 * <p>
 * 将固定阶段名与执行上下文中的调用 ID、追踪 ID 和期限关联。
 * 委托 {@link OperationObservation} 记录阶段耗时、剩余期限及已接入观测的 JDBC 统计，
 * 便于定位受理、调度、工具执行和结果提交中的耗时。
 * <p>
 * 本类只包装现有操作或记录生命周期点，不新增 SQL，也不调整业务期限、授权或事务决策。
 * 父阶段的耗时及 SQL 统计包含子阶段，分析时不能将父子阶段直接相加。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 16:52 ✾
 */
public final class ToolObservation {

    /**
     * 固定的阶段及生命周期点名称
     * <p>
     * 用于日志关联和性能统计，不代表执行状态机中的业务状态。
     */
    public enum Stage {
        /**
         * HTTP 入口解析当前身份并执行初始授权。
         */
        ENTRY_AUTHORIZATION,
        /**
         * 工具调用受理的整体过程，包含受理子阶段。
         */
        ADMISSION,
        /**
         * 按幂等键查找已受理调用，判断是否重放原回执。
         */
        REPLAY_LOOKUP,
        /**
         * 解析工具绑定及其对应的发布版本。
         */
        BINDING_RESOLUTION,
        /**
         * 加载 Schema 并校验输入及配置。
         */
        SCHEMA_VALIDATION,
        /**
         * 执行宿主侧工具调用权限及策略预检。
         */
        HOST_AUTHORIZATION,
        /**
         * 等待受理存储操作完成，包含调度等待和事务执行。
         */
        ADMISSION_COMMIT,
        /**
         * 同步受理事务的实际执行过程，覆盖事务结束。
         */
        ADMISSION_TRANSACTION,
        /**
         * 在受理事务内复核当前身份、权限及空间访问。
         */
        COMMIT_AUTHORIZATION,
        /**
         * 在受理事务内复核工具绑定、发布状态及版本材料。
         */
        COMMIT_GOVERNANCE,
        /**
         * 在受理事务内写入调用、快照、队列及相关事件。
         */
        COMMIT_WRITES,
        /**
         * 新调用的受理事务已实际提交的生命周期点。
         */
        ADMISSION_COMMITTED,
        /**
         * 幂等重放的受理事务已实际提交的生命周期点。
         */
        ADMISSION_REPLAY_COMMITTED,
        /**
         * 受理事务已实际回滚的生命周期点。
         */
        ADMISSION_ROLLED_BACK,
        /**
         * 受理事务完成状态未知的生命周期点，不等同于调用的业务状态。
         */
        ADMISSION_OUTCOME_UNKNOWN,
        /**
         * Worker 首次加载调用记录，包含读取记录的 SQL 耗时。
         */
        WORKER_LOAD,
        /**
         * Worker 已取得调用记录的生命周期点。
         */
        WORKER_RECORD_LOADED,
        /**
         * 加载执行信息并按调用类型选择调度路径。
         */
        DISPATCH_ROUTING,
        /**
         * 工具调度的整体过程，包含执行前检查、工具执行及结果处理。
         */
        DISPATCH,
        /**
         * 校验领取信息并加载待调度的调用。
         */
        DISPATCH_LOAD,
        /**
         * 调度前复核绑定、快照、权限、Schema 及期限。
         */
        PREFLIGHT,
        /**
         * 原子准备执行尝试、领取凭据、并发容量及免费预算状态。
         */
        PREPARE_DISPATCH,
        /**
         * 工具网关处理的整体过程。
         */
        GATEWAY,
        /**
         * 网关加载快照指定的工具绑定。
         */
        GATEWAY_BINDING,
        /**
         * 网关在执行前再次校验 Schema、权限及工具治理状态。
         */
        GATEWAY_PREFLIGHT,
        /**
         * 订阅工具适配器至取得执行结果，包含适配器内部的调度等待。
         */
        ADAPTER,
        /**
         * 校验工具输出的 Schema、身份、副作用声明及大小限制。
         */
        OUTPUT_VALIDATION,
        /**
         * 暂存工具执行结果，供终态提交使用。
         */
        RESULT_PERSIST,
        /**
         * 提交调用终态；该阶段正常完成不代表调用一定成功。
         */
        TERMINAL_COMMIT,
        /**
         * 根据已持久化的执行信息恢复处理，不重新调用工具业务逻辑。
         */
        RECOVERY
    }

    /**
     * 工具类只提供静态观测方法，不允许实例化。
     */
    private ToolObservation() {
    }

    /**
     * 使用执行上下文的期限包装异步阶段，订阅时才创建观测并调用操作工厂。
     * 每次订阅独立计时，本方法不会主动订阅、切换调度器、重试或缓存业务操作。
     *
     * @param stage   固定的观测阶段名，用于区分日志中的处理环节
     * @param context 可信执行上下文，提供调用 ID、追踪 ID 及用于诊断的期限
     * @param work    异步操作工厂，每次订阅时调用并返回该阶段的业务 Mono
     * @param <T>     异步操作发出的结果类型
     * @return 包含阶段观测的 Mono，保留源操作的成功、空结果、错误及取消信号
     */
    public static <T> Mono<T> mono(Stage stage, ExecutionContext context, Supplier<Mono<T>> work) {
        return mono(stage, context, context.deadline(), work);
    }

    /**
     * 使用显式期限包装异步阶段，适用于选定工具版本后已计算出更严格有效期限的场景。
     * 订阅及信号语义与 {@link #mono(Stage, ExecutionContext, Supplier)} 相同。
     *
     * @param stage    固定的观测阶段名
     * @param context  可信执行上下文，提供调用 ID 和追踪 ID；嵌套阶段沿用父阶段的追踪 ID
     * @param deadline 用于统计剩余时间的期限；不修改上下文，也不据此执行超时控制
     * @param work     异步操作工厂，每次订阅时调用并返回该阶段的业务 Mono
     * @param <T>      异步操作发出的结果类型
     * @return 包含阶段观测的 Mono，每次订阅创建独立的观测阶段
     */
    public static <T> Mono<T> mono(Stage stage, ExecutionContext context, Instant deadline, Supplier<Mono<T>> work) {
        return OperationObservation.observe(stage.name(), context.executionId(), context.traceId(), deadline, ignored -> work.get());
    }

    /**
     * 在当前线程同步执行操作并记录阶段，返回原结果，或继续抛出原操作的异常。
     * 若需观测完整事务，应由操作工厂包住实际事务执行及结束过程；本方法本身不创建或提交事务。
     * 调用方取消异步等待不会提前结束仍在 JDBC 线程运行的同步阶段计时。
     *
     * @param stage   固定的观测阶段名
     * @param context 可信执行上下文，提供调用 ID、追踪 ID 及用于诊断的期限
     * @param work    本次调用立即执行的同步操作工厂，其执行范围决定阶段的计时边界
     * @param <T>     同步操作的返回值类型
     * @return 同步操作的原始返回值
     */
    public static <T> T call(Stage stage, ExecutionContext context, Supplier<T> work) {
        return OperationObservation.call(stage.name(), context.executionId(), context.traceId(), context.deadline(), work);
    }

    /**
     * 记录某一生命周期事件的发生时间及剩余期限，不创建持续计时阶段，也不执行业务操作。
     * 存在当前观测阶段时沿用其追踪 ID，使历史调用的重放事件能关联本次请求；否则使用上下文的追踪 ID。
     *
     * @param stage   固定的生命周期点名称，例如事务已提交或已回滚
     * @param context 可信执行上下文，提供原调用 ID、期限及无当前观测阶段时使用的追踪 ID
     */
    public static void point(Stage stage, ExecutionContext context) {
        var current = OperationObservation.current();
        OperationObservation.point(stage.name(), context.executionId(), current == null ? context.traceId() : current.traceId(), context.deadline());
    }
}
