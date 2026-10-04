package com.arte.ainew.spi.persistence;

/**
 * 执行存储服务
 * 明确保存 Invocation／Attempt、归属、版本、结果引用；输出批次与重放游标归 EventStore。
 * <p>
 * 主要操作：Invocation／Attempt 记录、条件更新、关联工作项与结果查询等。
 * 边界：AI 定义状态契约，基础设施实现；Job 调度状态由公共 JobScheduler 管理，Run 历史另有权威。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:40 ✾
 **/
public interface ExecutionStore {
}
