package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

/**
 * 生成调用人工费用对账的持久化接口，提供待对账分页、原子确认及耐久回执查询。
 * <p>
 * 应用层负责校验当前身份的读取或预算管理权限，并接收管理员对外部账单及远端执行结束事实的确认；
 * 存储层负责校验数据归属、版本、状态和幂等性，不主动查询供应商账单。
 * 对账确认须在同一事务中完成费用记账、预留释放、未知执行状态收敛、回执及事件保存，
 * 任一步失败均整体回滚，不得重复扣费或覆盖已经最终结算的费用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public interface ReconciliationStore {

    /**
     * 分页读取指定预算账户下已进入终态、但预算仍处于预留或待对账状态的生成调用。
     * 查询只读取数据，不释放预留、不结算费用，也不改变执行状态。
     *
     * @param owner     当前认证身份对应的数据归属范围，用于校验预算账户归属及过滤调用
     * @param budgetRef 待查询的预算账户引用，须属于指定 owner
     * @param current   页码，从 1 开始
     * @param size      每页记录数，范围为 1～100
     * @return 包含页码、每页大小、总记录数及待对账记录的分页结果；无匹配记录时列表为空，
     * 账户不存在或归属不符时通过响应式错误返回
     */
    Mono<ConversationPage<Reconciliation.Pending>> pendingReconciliations(ExecutionOwner owner, String budgetRef, long current, long size);

    /**
     * 根据已核实的实际费用及执行结束凭据，原子提交人工对账并保存不可变回执。
     * <p>
     * 首次确认须校验调用与预留记录的归属、关联关系、版本、可结算状态及币种，
     * 释放本次预留并增加实际扣费。UNKNOWN 调用根据耐久停止标记收敛为 CANCELLED 或 FAILED，
     * 保留已有部分结果，并阻止旧 Worker 覆盖核对结果；已知成功结果保持成功。
     * 会话门闩只按原调用 ID 有条件释放，不影响后续调用。
     * <p>
     * 同一调用下同键同内容返回原回执，不重复记账，重放先于版本检查；
     * 同键不同内容拒绝，已最终结算的预留不得以新键再次结算。
     *
     * @param command 对账命令，包含数据归属、核对主体、追踪 ID、幂等键、预算及调用和预留标识、
     *                预期版本、实际费用、凭据引用、核对说明及远端执行已结束的确认；
     *                须由完成授权校验的应用层组装
     * @return 首次成功为 APPLIED 并携带回执，同键同内容重放为 REPLAYED 并携带原回执；
     * 归属、版本、状态、币种或幂等冲突返回对应的拒绝结果，持久化异常通过响应式错误返回
     */
    Mono<StoreOutcome<Reconciliation.Receipt>> confirmReconciliation(Reconciliation.Confirm command);

    /**
     * 按原人工对账幂等键读取已持久化的回执，供结果核验及网络不确定后的查询使用。
     * 仅查询历史确认结果，不触发新的对账、费用结算或模型调用。
     *
     * @param owner        当前认证身份对应的数据归属范围，用于校验调用归属
     * @param invocationId 原人工对账请求中的模型调用 ID
     * @param key          原人工对账请求的 Idempotency-Key，不是模型调用、停止或重新生成操作的幂等键
     * @return 已保存的不可变对账回执；调用不存在、归属不符或指定键没有回执时返回 Mono.empty()
     */
    Mono<Reconciliation.Receipt> reconciliationReceipt(ExecutionOwner owner, String invocationId, String key);
}
