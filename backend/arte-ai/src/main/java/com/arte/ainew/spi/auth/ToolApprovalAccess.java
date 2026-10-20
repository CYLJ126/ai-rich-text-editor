package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import reactor.core.publisher.Mono;

/**
 * 审批的当前宿主资格；固定名单不代表当前成员／操作权限，不提供默认放行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public interface ToolApprovalAccess {

    /**
     * 提交前及重放时检查政策中的审批人仍有效；提交回调须在原物理事务再次执行。
     */
    Mono<PlanPermit> authorizePlan(ToolExecutionSnapshot snapshot, ToolApproval.Plan plan, ExecutionContext requester);

    Mono<Permit> authorize(ToolApproval approval, ToolExecutionSnapshot snapshot, ExecutionContext actor, boolean deciding);

    interface PlanPermit {
        void requireInCurrentTransaction(ToolExecutionSnapshot snapshot, ToolApproval.Plan plan);
    }

    interface Permit {
        void requireInCurrentTransaction(ToolApproval approval, ToolExecutionSnapshot snapshot);
    }
}
