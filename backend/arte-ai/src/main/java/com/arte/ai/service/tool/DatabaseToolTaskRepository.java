package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolResponse;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.ToolTaskRepository;
import com.arte.ai.mapper.tool.ToolCallResultMapper;
import com.arte.ai.mapper.tool.ToolTaskMapper;
import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolTask;
import com.arte.ai.pojo.tool.ToolTaskHandle;
import com.arte.ai.pojo.tool.po.ToolCallResultPo;
import com.arte.ai.pojo.tool.po.ToolTaskPo;
import com.arte.ai.service.tool.security.ToolDataSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 MySQL 乐观锁和租约 SQL 的工具任务仓库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Repository
@RequiredArgsConstructor
public class DatabaseToolTaskRepository implements ToolTaskRepository {

    private static final String META_NAMESPACE = "toolNamespace";
    private static final String META_NAME = "toolName";
    private static final String META_VERSION = "toolVersion";

    private final ToolTaskMapper taskMapper;
    private final ToolCallResultMapper resultMapper;
    private final ObjectMapper objectMapper;
    private final ToolDataSanitizer sanitizer;

    @Override
    public void saveTask(ToolTask task) {
        taskMapper.insert(toPo(task));
    }

    @Override
    public Optional<ToolTask> findTask(String taskId) {
        return taskMapper.selectByTaskId(taskId).map(this::toDomain);
    }

    @Override
    public Optional<ToolTask> findByCallId(String callId) {
        return taskMapper.selectByCallId(callId).map(this::toDomain);
    }

    @Override
    public Optional<ToolTask> findByResumeTokenHash(String resumeTokenHash) {
        return taskMapper.selectByResumeTokenHash(resumeTokenHash).map(this::toDomain);
    }

    @Override
    public boolean tryClaim(String taskId, String workerId, Instant leaseUntil) {
        return taskMapper.tryClaim(taskId, workerId, toLocal(leaseUntil), LocalDateTime.now()) == 1;
    }

    @Override
    public boolean renewClaim(String taskId, String workerId, Instant leaseUntil) {
        return taskMapper.renewLease(taskId, workerId, toLocal(leaseUntil)) == 1;
    }

    @Override
    public boolean saveState(ToolTask task, long expectedVersion) {
        return taskMapper.updateState(toPo(task), expectedVersion) == 1;
    }

    @Override
    public void saveResult(String taskId, ToolResult<? extends ToolResponse> result) {
        ToolTask task = findTask(taskId).orElseThrow(() -> new IllegalArgumentException("unknown task: " + taskId));
        ToolCallResultPo po = ResultPersistenceMapper.toPo(result, task.callId(), taskId,
                objectMapper, sanitizer);
        resultMapper.insert(po);
    }

    @Override
    public Optional<ToolResult<? extends ToolResponse>> findResult(String taskId) {
        return resultMapper.selectByTaskId(taskId).map(po -> ResultPersistenceMapper.fromPo(po, objectMapper));
    }

    @Override
    public List<ToolTask> findRecoverable(int limit) {
        return taskMapper.selectRecoverable(LocalDateTime.now(), limit).stream().map(this::toDomain).toList();
    }

    private ToolTaskPo toPo(ToolTask task) {
        Map<String, Object> metadata = new LinkedHashMap<>(task.metadata());
        metadata.put(META_NAMESPACE, task.tool().namespace());
        metadata.put(META_NAME, task.tool().name());
        metadata.put(META_VERSION, task.tool().version());
        ToolTaskPo po = new ToolTaskPo()
                .setTaskId(task.taskId()).setCallId(task.callId())
                .setStatus(task.status().name().toLowerCase())
                .setArgumentsSnapshot(task.arguments())
                .setExecutionPolicy(objectMapper.convertValue(task.executionPolicy(), Map.class))
                .setOwnerId(task.ownerId()).setSubjectId(task.subjectId()).setTraceId(task.traceId())
                .setIdempotencyKey(task.idempotencyKey())
                .setCredentialBindingId(task.credentialBindingId())
                .setProgress(task.progress() == null ? null : BigDecimal.valueOf(task.progress()))
                .setProgressMessage(task.progressMessage()).setResumeTokenHash(task.resumeToken())
                .setAttempt(task.attempt())
                .setNextAttemptAt(toLocalNullable(task.nextAttemptAt()))
                .setWorkerId(task.workerId()).setLeaseUntil(toLocalNullable(task.leaseUntil()))
                .setMetadata(Map.copyOf(metadata)).setRowVersion(task.version());
        po.setCreateBy(task.ownerId());
        po.setUpdateBy(task.ownerId());
        return po;
    }

    private ToolTask toDomain(ToolTaskPo po) {
        Map<String, Object> metadata = po.getMetadata() == null ? Map.of() : po.getMetadata();
        ToolReference reference = new ToolReference(String.valueOf(metadata.get(META_NAMESPACE)),
                String.valueOf(metadata.get(META_NAME)), String.valueOf(metadata.get(META_VERSION)));
        return new ToolTask(po.getTaskId(), po.getCallId(), reference,
                po.getArgumentsSnapshot(), objectMapper.convertValue(po.getExecutionPolicy(), ToolExecutionPolicy.class),
                po.getOwnerId(), po.getSubjectId(), po.getTraceId(), po.getIdempotencyKey(),
                po.getCredentialBindingId(), toInstant(po.getCreateTime()), metadata,
                ToolTaskHandle.Status.valueOf(po.getStatus().toUpperCase()),
                po.getProgress() == null ? null : po.getProgress().doubleValue(), po.getProgressMessage(),
                po.getResumeTokenHash(), toInstant(po.getUpdateTime()), po.getRowVersion(), po.getAttempt(),
                toInstantNullable(po.getNextAttemptAt()), po.getWorkerId(), toInstantNullable(po.getLeaseUntil()));
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private LocalDateTime toLocalNullable(Instant value) {
        return value == null ? null : toLocal(value);
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Instant toInstantNullable(LocalDateTime value) {
        return value == null ? null : toInstant(value);
    }
}
