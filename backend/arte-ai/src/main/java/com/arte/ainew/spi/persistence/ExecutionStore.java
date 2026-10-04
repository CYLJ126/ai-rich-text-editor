package com.arte.ainew.spi.persistence;

/**
 * 执行存储服务
 * 明确保存 Invocation／Attempt、归属、版本、结果引用；输出批次与重放游标归 EventStore。
 * <p>
 * 主要操作：Invocation／Attempt 记录、条件更新、关联工作项与结果查询等。
 * 边界：AI 定义状态契约，基础设施实现；Job 调度状态由公共 JobScheduler 管理，Run 历史另有权威。
 * <p>
 * 未来方法须落实幂等作用域／摘要唯一约束、version 与 fencingToken 条件更新、Attempt 序号唯一性，
 * 以及终态／结果引用／Outbox 的提交边界。UNKNOWN 仅经远端核对收敛，不重新派发。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:40 ✾
 **/
public interface ExecutionStore {
}
