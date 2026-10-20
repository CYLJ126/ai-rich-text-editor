package com.arte.ainew.application.tool.approval;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;

/**
 * 人工窗口与固定名单由服务端政策产生，预览和提交共用，重放沿用原绝对窗口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public final class ToolApprovalPolicy {

    public static final Duration MARGIN = Duration.ofSeconds(30);
    public static final Duration CONFIRMATION = Duration.ofMinutes(2);
    public static final Duration INDEPENDENT = Duration.ofMinutes(15);
    private final NewAiToolApprovalProperties properties;
    private final ToolApprovalAccess access;
    private final ToolApprovalStore store;

    public ToolApprovalPolicy(NewAiToolApprovalProperties properties, ToolApprovalAccess access, ToolApprovalStore store) {
        this.properties = properties;
        this.access = access;
        this.store = store;
    }

    public Mono<Prepared> prepare(ToolExecutionSnapshot snapshot, ExecutionContext current, boolean replay) {
        if (snapshot.policy().approvalMode() == ToolExecutionPolicy.ApprovalMode.NONE)
            return Mono.just(new Prepared(null, null));
        return Mono.defer(() -> {
            if (!properties.enabled() || access == null) return Mono.error(denied());
            return (replay ? store.find(snapshot.binding().owner(), snapshot.request().context().executionId())
                    .map(ToolApproval::plan).switchIfEmpty(Mono.error(denied())) : Mono.fromCallable(() -> plan(snapshot)))
                    .flatMap(plan -> access.authorizePlan(snapshot, plan, current).map(permit -> new Prepared(plan, permit))
                            .switchIfEmpty(Mono.error(denied())));
        });
    }

    private ToolApproval.Plan plan(ToolExecutionSnapshot snapshot) {
        boolean independent = snapshot.policy().approvalMode() == ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL;
        var deadline = snapshot.request().options().deadline();
        var requestedEnd = snapshot.receivedAt().plus(independent ? INDEPENDENT : CONFIRMATION);
        // 独立审批必须完整容纳 15 分钟，不能静默缩短；本人确认可扣除原期限内的执行余量。
        if (independent && deadline.isBefore(requestedEnd.plus(MARGIN))) throw windowDenied();
        var end = requestedEnd.isBefore(deadline.minus(MARGIN)) ? requestedEnd : deadline.minus(MARGIN);
        if (!end.isAfter(snapshot.receivedAt())) throw windowDenied();
        Set<String> subjects = independent ? properties.approvers().stream()
                .filter(a -> a.tenantId().equals(snapshot.binding().owner().tenantId()) && a.workspaceId().equals(snapshot.binding().owner().workspaceId()))
                .findFirst().map(NewAiToolApprovalProperties.Approvers::subjectIds).orElse(Set.of()) : Set.of();
        if (independent && (subjects.isEmpty() || subjects.contains(snapshot.binding().owner().subjectId())))
            throw denied();
        var plan = new ToolApproval.Plan(snapshot.policy().approvalMode(), subjects, end, MARGIN);
        plan.requireMatches(snapshot);
        return plan;
    }

    /**
     * 预览摘要不含绝对时刻；固定审批人、窗口政策与执行方案全部参与，改名单须重新预览。
     */
    public static String previewDigest(ToolExecutionSnapshot snapshot, ToolApproval.Plan plan) {
        if (plan == null) return ToolAdmissionDigests.plan(snapshot);
        return CanonicalJson.digest(CanonicalJson.fields("format", "tool-approval-preview-v1", "execution", ToolAdmissionDigests.plan(snapshot),
                "mode", plan.mode(), "subjects", new TreeSet<>(plan.approverSubjectIds()), "margin", plan.executionMargin(),
                "window", plan.mode() == ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL ? INDEPENDENT : CONFIRMATION));
    }

    public record Prepared(ToolApproval.Plan plan, ToolApprovalAccess.PlanPermit permit) {
    }

    private static AdmissionException denied() {
        return new AdmissionException(ResultCodeEnum.AI_TOOL_APPROVAL_UNAVAILABLE);
    }

    private static AdmissionException windowDenied() {
        return new AdmissionException(ResultCodeEnum.AI_TOOL_APPROVAL_WINDOW_INVALID);
    }
}
