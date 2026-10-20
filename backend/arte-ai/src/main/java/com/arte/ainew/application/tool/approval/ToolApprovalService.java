package com.arte.ainew.application.tool.approval;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolApprovalCommand;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 公开审批用例：当前身份定位参与者，先授权再投影；决定始终回到原调用的物理事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public final class ToolApprovalService {

    private final ToolApprovalStore approvals;
    private final ToolAdmissionStore snapshots;
    private final ExecutionStore executions;
    private final ToolApprovalAccess access;

    public ToolApprovalService(ToolApprovalStore approvals, ToolAdmissionStore snapshots, ExecutionStore executions, ToolApprovalAccess access) {
        this.approvals = approvals;
        this.snapshots = snapshots;
        this.executions = executions;
        this.access = access;
    }

    public record Detail(ToolApproval approval, ToolExecutionSnapshot snapshot, Invocation invocation,
                         boolean mayDecide) {
    }

    public record Inbox(List<Detail> records, String nextCursor, boolean exhausted) {
    }

    public record Decision(ToolApproval approval, Invocation invocation) {
    }

    public Mono<Detail> get(String approvalId, String invocationId, ExecutionContext context) {
        return approvals.visible(ExecutionOwner.from(context), approvalId, invocationId)
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(approval -> detail(approval, context));
    }

    private Mono<Detail> detail(ToolApproval approval, ExecutionContext context) {
        return snapshots.snapshot(approval.owner(), approval.invocationId()).zipWith(executions.find(approval.owner(), approval.invocationId()))
                .switchIfEmpty(Mono.error(new IllegalStateException("Approval fixed execution missing")))
                .flatMap(pair -> {
                    var snapshot = pair.getT1();
                    if (!approval.subjectDigest().equals(ToolApproval.digest(snapshot, approval.plan())) || !snapshot.request().equals(pair.getT2().request()))
                        return Mono.error(new IllegalStateException("Approval fixed content mismatch"));
                    return access.authorize(approval, snapshot, context, false).switchIfEmpty(Mono.error(new AccessDeniedException("Approval access denied")))
                            .then(access.authorize(approval, snapshot, context, true).map(ignored -> true)
                                    .defaultIfEmpty(false).onErrorResume(AccessDeniedException.class, ignored -> Mono.just(false)))
                            .map(allowed -> new Detail(approval, snapshot, pair.getT2(), allowed && approval.state() == ToolApproval.State.PENDING));
                });
    }

    public Mono<Inbox> list(String cursor, int limit, ExecutionContext context) {
        ContractChecks.range(limit, "approval page size", 1, 20);
        return approvals.inbox(ExecutionOwner.from(context), cursor, limit).flatMap(page -> Flux.fromIterable(page.records())
                .concatMap(approval -> detail(approval, context).onErrorResume(AccessDeniedException.class, ignored -> Mono.empty()))
                .collectList().map(records -> new Inbox(records, page.nextCursor(), page.exhausted())));
    }

    public Mono<Decision> decide(String approvalId, long version, String digest, ToolApprovalCommand.Decision decision,
                                 String key, ExecutionContext context) {
        return get(approvalId, null, context).flatMap(detail -> access.authorize(detail.approval(), detail.snapshot(), context, true)
                        .switchIfEmpty(Mono.error(new AccessDeniedException("Approval decision denied")))
                        .flatMap(permit -> approvals.decide(new ToolApprovalCommand(detail.approval().owner(), detail.approval().invocationId(),
                                approvalId, version, digest, decision, key), ExecutionOwner.from(context), permit::requireInCurrentTransaction)))
                .map(result -> {
                    if (!result.successful()) throw AdmissionException.fromStoreRejection(result.code());
                    return new Decision(result.value().approval(), result.value().invocation());
                });
    }
}
