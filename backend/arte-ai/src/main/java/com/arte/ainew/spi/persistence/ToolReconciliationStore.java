package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.ainew.pojo.tool.ToolReconciliation;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import reactor.core.publisher.Mono;

/**
 * 工具业务结果的人工核对存储端口，提供进度查看、待办查询、任务认领、证据读取和核对决定提交能力。
 * 每次操作均依据当前宿主 Permit 校验真实主体及工作空间权限，提交时复核原调用身份和版本。
 * 人工确认复用自动核对的业务结果收尾规则，在短事务内保存决定与审计，不重新执行原工具操作。
 * 本端口处理业务结果核对，不负责费用或预算对账。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
public interface ToolReconciliationStore {

    /**
     * 在当前授权范围内读取指定原调用的核对详情、处理进度及可执行的人工操作。
     *
     * @param managed false 时仅允许原调用主体查看自身进度；true 时要求当前人工核对管理权限
     */
    Mono<ToolReconciliation.Detail> reconciliation(ToolManagementAccess.Permit permit, String invocationId, boolean managed);

    /**
     * 按游标有界分页查询当前租户、工作空间内尚未解决的核对待办，并逐项校验当前管理权限。
     * 返回的下一页游标仅用于继续遍历，不代表锁定整个待办列表的快照。
     */
    Mono<ToolReconciliation.Page> reconciliations(ToolManagementAccess.Permit permit, String cursor, int limit);

    /**
     * 提交认领、请求核对、确认结果或标记无法确认的命令，原子保存处理决定及审计记录。
     * 新命令校验原调用、原操作及预期版本；幂等重放仍须通过当前授权，确认结果须由独立认领人核验权威证据。
     * 命令不得抢占有效自动核对租约、重置查询限额或重新执行原工具操作。
     *
     * @param key 当前主体范围内的命令幂等键，相同键不得用于不同命令内容
     * @return 命令处理回执，包含处理结果及版本；不等同于原工具业务执行成功
     */
    Mono<ToolReconciliation.Receipt> reviewCommand(ToolManagementAccess.Permit permit, String key, ToolReconciliation.Command command);

    /**
     * 为已认领任务的独立复核人在当前短事务内读取原操作的权威证据，不消费自动查询额度。
     * 此方法仅提供核验材料；提交确认时仍须重新读取证据，查无回执不能证明原操作未执行。
     */
    Mono<ToolOutcomeEvidence> reviewEvidence(ToolManagementAccess.Permit permit, String invocationId);
}
