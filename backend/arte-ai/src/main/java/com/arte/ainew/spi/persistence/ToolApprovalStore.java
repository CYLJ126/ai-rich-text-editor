package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 审批同库事务端口，仅供可信应用层；不得直接注册为 HTTP 服务。
 * authorization 在物理事务内执行，首次决定和幂等重放都必须重新验证当前身份、成员、操作及资源权限。
 * 独立审批缺少宿主当前成员／审批权限校验时不得调用，不提供默认授权实现。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public interface ToolApprovalStore {

    /**
     * 内部定位，不授予读取权；对外展示由宿主另行验证当前资格。
     */
    Mono<ToolApproval> find(ExecutionOwner owner, String invocationId);

    /**
     * actor 来自当前可信身份；批准不替换 snapshot 内原发起者。
     */
    Mono<StoreOutcome<ToolApprovalReceipt>> decide(ToolApprovalCommand command, ExecutionOwner actor,
                                                   BiConsumer<ToolApproval, ToolExecutionSnapshot> authorization);

    /**
     * 有界系统恢复，每条独立事务，与决定和取消共用 Invocation 行锁；多实例可重复扫描。
     */
    Mono<Integer> expire(int limit);

    /**
     * 按当前身份完整范围的耐久参与者索引定位；不接受正文提供其他 owner。仍须应用层当前授权。
     */
    default Mono<ToolApproval> visible(ExecutionOwner actor, String approvalId, String invocationId) {
        return Mono.error(new UnsupportedOperationException("Approval audience lookup unavailable"));
    }

    record Inbox(List<ToolApproval> records, String nextCursor, boolean exhausted) {
    }

    default Mono<Inbox> inbox(ExecutionOwner actor, String cursor, int limit) {
        return Mono.error(new UnsupportedOperationException("Approval inbox unavailable"));
    }

    /**
     * 网关必须验证批准与当前真实 Attempt，不能只检查调用参数中声明已批准。
     */
    default Mono<Void> requireApproved(GatewayCall<ToolInvocation> call) {
        return Mono.error(new UnsupportedOperationException("Approval dispatch verification unavailable"));
    }

    /**
     * 业务写入必须在同一物理写事务内持有调用／批准／Attempt 锁；不得以异步初检代替提交门控。
     */
    default void requireApprovedInCurrentTransaction(GatewayCall<ToolInvocation> call) {
        throw new UnsupportedOperationException("Transactional domain write fencing unavailable");
    }

    /**
     * 先锁调用／Attempt，再取得当前平台资格，最后调用本门控持有目录／绑定治理锁直到领域提交。
     */
    default void requireCurrentBindingInCurrentTransaction(GatewayCall<ToolInvocation> call) {
        throw new UnsupportedOperationException("Transactional binding governance unavailable");
    }
}
