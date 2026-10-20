package com.arte.ainew.web.response;

import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.ainew.pojo.tool.ToolReconciliation;
import com.arte.ainew.serialization.CanonicalJson;

import java.time.Instant;
import java.util.List;

import static com.arte.ainew.web.ToolManagementValues.json;

/**
 * 只返回当前可见的原操作与进度；不暴露内部身份授权、租约凭证或原始内部快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
public record ToolReconciliationResponse(String invocationId, String attemptId, String operationKey, String version,
                                         String invocationVersion,
                                         String state, String invocationState, String title, String argumentsJson,
                                         String requesterSubjectId, int checks, int maxChecks,
                                         String createdAt, String nextCheckAt, String autoExpiresAt, String lastCode,
                                         String responsibleSubjectId, String nextStep, String dueAt,
                                         boolean unassigned, boolean overdue, boolean mayConfirm, boolean mayClaim,
                                         boolean evidenceSupported, String serverTime, List<Audit> history) {

    public static ToolReconciliationResponse from(ToolReconciliation.Detail d) {
        var w = d.work();
        var r = d.review();
        return new ToolReconciliationResponse(w.invocationId(), w.attemptId(), w.operationKey(), Long.toString(w.version()), Long.toString(d.invocation().version()),
                w.state().name(), d.invocation().state().name(), d.snapshot().binding().tool().tool().title(), d.argumentsVisible() ? CanonicalJson.encode(json(d.snapshot().input().arguments())) : "该工具尚未登记跨主体材料读取资格，固定参数不对核对管理视图开放",
                d.invocation().request().context().authorization().principal().subjectId(), w.checks(), 6, time(w.createdAt()), time(w.nextCheck()), time(w.validUntil()), w.lastCode(),
                r.assignee(), r.nextStep(), time(r.dueAt()), r.assignee().isEmpty(), r.dueAt() <= d.serverTime(), d.mayConfirm(), d.mayClaim(), d.evidenceSupported(), time(d.serverTime()), d.history().stream().map(Audit::from).toList());
    }

    public record Audit(String commandKey, String actorId, String action, String outcome, String caseVersion,
                        String invocationVersion, String note, String nextStep, String recordedAt) {
        static Audit from(ToolReconciliation.Audit a) {
            return new Audit(a.commandKey(), a.actorId(), a.action(), a.outcome(), Long.toString(a.caseVersion()), Long.toString(a.invocationVersion()), a.note(), a.nextStep(), time(a.recordedAt()));
        }
    }

    private static String time(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    public record Page(List<ToolReconciliationResponse> records, String nextCursor, boolean exhausted) {
    }

    public record Evidence(String decision, String reference, String digest, String sideEffect, String outputJson) {
        public static Evidence from(ToolOutcomeEvidence e) {
            return new Evidence(e.decision().name(), e.reference(), e.digest(), e.result() == null ? null : e.result().sideEffect().name(),
                    e.result() == null || e.result().output() == null ? null : CanonicalJson.encode(json(e.result().output())));
        }
    }
}
