package com.arte.ai.service.tool.security;

import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.ToolApprovalPage;
import com.arte.ai.pojo.tool.ToolApprovalView;
import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 当前用户审批记录的只读查询服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolApprovalQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> STATUSES = Set.of("pending", "approved", "rejected", "expired", "all");

    private final ToolApprovalMapper approvalMapper;

    public ToolApprovalPage listOwned(String ownerId, String status, int current, int size) {
        String normalizedStatus = normalizeStatus(status);
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        long offset = (long) (safeCurrent - 1) * safeSize;
        var records = approvalMapper.selectOwned(ownerId, normalizedStatus, offset, safeSize)
                .stream().map(this::toView).toList();
        return new ToolApprovalPage(records, approvalMapper.countOwned(ownerId, normalizedStatus),
                safeCurrent, safeSize);
    }

    public Optional<ToolApprovalView> findOwned(String ownerId, String requestId) {
        return approvalMapper.selectByRequestId(requestId)
                .filter(value -> ownerId.equals(value.getCreateBy()))
                .map(this::toView);
    }

    private String normalizeStatus(String value) {
        String status = value == null || value.isBlank()
                ? "pending" : value.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported approval status: " + value);
        }
        return status;
    }

    private ToolApprovalView toView(ToolApprovalPo value) {
        String status = value.getStatus();
        if ("pending".equalsIgnoreCase(status) && value.getExpiresAt() != null
                && !value.getExpiresAt().isAfter(LocalDateTime.now())) {
            status = "expired";
        }
        return new ToolApprovalView(value.getId() == null ? 0 : value.getId(), value.getRequestId(),
                value.getCallId(), value.getTaskId(), value.getWorkflowRunId(), value.getToolId(),
                value.getToolVersion(), value.getArgumentsDigest(), value.getDisplayArguments(),
                value.getSummary(), status, value.getApproverId(), value.getDecisionReason(),
                toInstant(value.getExpiresAt()), toInstant(value.getDecidedAt()),
                toInstant(value.getCreateTime()), toInstant(value.getUpdateTime()));
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
