package com.arte.ainew.web.response;

import com.arte.ainew.application.tool.approval.ToolApprovalService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.web.ToolManagementValues;

import java.util.List;

/**
 * 审批展示白名单，精确参数用 JSON 原文；不返回内部授权、凭据或执行器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public record ToolApprovalResponse(String approvalId, String invocationId, String version, String subjectDigest,
                                   String mode, String state, String requesterSubjectId,
                                   List<String> approverSubjectIds,
                                   String title, String toolVersion, String bindingVersion, String argumentsJson,
                                   String configurationJson,
                                   String sideEffect, String expiresAt, String executeNotAfter, String decidedBy,
                                   String decidedAt,
                                   String invocationState, boolean mayDecide, boolean requester, String serverTime) {

    public static ToolApprovalResponse from(ToolApprovalService.Detail detail, ExecutionContext actor) {
        var a = detail.approval();
        var s = detail.snapshot();
        return new ToolApprovalResponse(a.approvalId(), a.invocationId(), Long.toString(a.version()), a.subjectDigest(),
                a.plan().mode().name(), a.state().name(), a.owner().subjectId(), a.plan().approverSubjectIds().stream().sorted().toList(),
                s.binding().tool().tool().title(), s.request().input().tool().version(), s.binding().revision().version(),
                CanonicalJson.encode(ToolManagementValues.json(s.input().arguments())),
                CanonicalJson.encode(ToolManagementValues.json(s.binding().effectiveConfiguration())), s.binding().tool().tool().risk().sideEffect().name(),
                a.plan().expiresAt().toString(), s.request().options().deadline().toString(), a.decidedBy(),
                a.decidedAt() == null ? null : a.decidedAt().toString(), detail.invocation().state().name(), detail.mayDecide(),
                a.owner().subjectId().equals(actor.authorization().principal().subjectId()), java.time.Instant.now().toString());
    }

    public record Summary(String approvalId, String invocationId, String title, String mode, String expiresAt,
                          boolean mayDecide) {
        public static Summary from(ToolApprovalService.Detail detail) {
            return new Summary(detail.approval().approvalId(), detail.approval().invocationId(), detail.snapshot().binding().tool().tool().title(),
                    detail.approval().plan().mode().name(), detail.approval().plan().expiresAt().toString(), detail.mayDecide());
        }
    }

    public record Page(List<Summary> records, String nextCursor, boolean exhausted) {
    }

    public record Receipt(String approvalId, String invocationId, String version, String state, String invocationState,
                          String decidedAt) {
        public static Receipt from(ToolApprovalService.Decision value) {
            return new Receipt(value.approval().approvalId(), value.approval().invocationId(), Long.toString(value.approval().version()),
                    value.approval().state().name(), value.invocation().state().name(), value.approval().decidedAt().toString());
        }
    }
}
