package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.workflow.WorkflowCompiler;
import com.arte.ai.api.tool.workflow.WorkflowManager;
import com.arte.ai.api.tool.workflow.WorkflowValidator;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.WorkflowMapper;
import com.arte.ai.mapper.tool.WorkflowVersionMapper;
import com.arte.ai.pojo.tool.CompiledWorkflow;
import com.arte.ai.pojo.tool.ToolPrincipal;
import com.arte.ai.pojo.tool.WorkflowDefinition;
import com.arte.ai.pojo.tool.WorkflowValidationResult;
import com.arte.ai.pojo.tool.po.WorkflowPo;
import com.arte.ai.pojo.tool.po.WorkflowVersionPo;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 数据库持久化的不可变工作流版本管理服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
@RequiredArgsConstructor
public class DefaultWorkflowManager implements WorkflowManager {

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowValidator validator;
    private final WorkflowCompiler compiler;
    private final WorkflowPersistenceCodec codec;
    private final ToolDistributedLockExecutor lockExecutor;
    private final TransactionTemplate transactionTemplate;

    @Override
    public WorkflowDefinition saveDraft(ToolPrincipal principal, WorkflowDefinition definition) {
        return saveDraft(principal, definition, null);
    }

    @Override
    public WorkflowDefinition saveDraft(ToolPrincipal principal, WorkflowDefinition definition,
                                        Long expectedRowVersion) {
        Objects.requireNonNull(principal, "principal");
        String ownerId = principal.ownerId();
        requireOwner(ownerId);
        return lockExecutor.execute(lockKey(ownerId, definition.workflowId()), () ->
                transactionTemplate.execute(status -> saveLocked(ownerId, definition, expectedRowVersion)));
    }

    @Override
    public WorkflowValidationResult validate(ToolPrincipal principal, WorkflowDefinition definition) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.ownerId().isBlank()) {
            WorkflowPo existing = workflowMapper.selectByWorkflowId(definition.workflowId()).orElse(null);
            if (existing != null && !principal.ownerId().equals(existing.getOwnerId())) {
                throw new SecurityException("workflow belongs to another owner");
            }
        }
        return validator.validate(definition, principal);
    }

    @Override
    public CompiledWorkflow publish(ToolPrincipal principal, String workflowId, String version,
                                    long expectedRowVersion) {
        Objects.requireNonNull(principal, "principal");
        String ownerId = principal.ownerId();
        requireOwner(ownerId);
        return lockExecutor.execute(lockKey(ownerId, workflowId), () ->
                transactionTemplate.execute(status -> publishLocked(ownerId, workflowId, version,
                        expectedRowVersion, principal)));
    }

    @Override
    public Optional<WorkflowDefinition> find(String ownerId, String workflowId, String version) {
        if (workflowMapper.selectOwned(workflowId, ownerId).isEmpty()) return Optional.empty();
        return versionMapper.selectExact(workflowId, version).map(this::toDefinition);
    }

    @Override
    public List<WorkflowDefinition> listVersions(String ownerId, String workflowId) {
        if (workflowMapper.selectOwned(workflowId, ownerId).isEmpty()) return List.of();
        return versionMapper.selectVersions(workflowId).stream().map(this::toDefinition).toList();
    }

    private WorkflowDefinition saveLocked(String ownerId, WorkflowDefinition definition,
                                          Long expectedRowVersion) {
        WorkflowPo workflow = workflowMapper.selectByWorkflowId(definition.workflowId()).orElse(null);
        if (workflow != null && !ownerId.equals(workflow.getOwnerId())) {
            throw new SecurityException("workflow belongs to another owner");
        }
        if (workflow == null) {
            workflow = new WorkflowPo().setWorkflowId(definition.workflowId()).setOwnerId(ownerId)
                    .setName(definition.name()).setDescription(definition.description())
                    .setLifecycleState(ToolLifecycleStateEnum.DRAFT);
            workflow.setCreateBy(ownerId);
            workflow.setUpdateBy(ownerId);
            workflowMapper.insert(workflow);
        } else if (workflowMapper.updateMetadata(definition.workflowId(), ownerId,
                definition.name(), definition.description()) != 1) {
            throw new IllegalStateException("failed to update workflow metadata");
        }
        WorkflowVersionPo existingVersion = versionMapper.selectExact(
                definition.workflowId(), definition.version()).orElse(null);
        if (existingVersion != null) {
            if (existingVersion.getLifecycleState() != ToolLifecycleStateEnum.DRAFT) {
                throw new IllegalStateException("published workflow versions are immutable; create a new version");
            }
            if (expectedRowVersion == null) {
                throw new IllegalArgumentException("expectedRowVersion is required when updating a draft");
            }
            WorkflowVersionPo update = toPo(definition, null, ToolLifecycleStateEnum.DRAFT);
            update.setUpdateBy(ownerId);
            if (versionMapper.updateDraft(update, expectedRowVersion) != 1) {
                throw new IllegalStateException("workflow draft changed concurrently");
            }
            return definition;
        }
        WorkflowVersionPo po = toPo(definition, null, ToolLifecycleStateEnum.DRAFT);
        po.setCreateBy(ownerId);
        po.setUpdateBy(ownerId);
        versionMapper.insert(po);
        return definition;
    }

    private CompiledWorkflow publishLocked(String ownerId, String workflowId, String version,
                                           long expectedRowVersion, ToolPrincipal principal) {
        workflowMapper.selectOwned(workflowId, ownerId)
                .orElseThrow(() -> new SecurityException("workflow is unavailable"));
        WorkflowVersionPo stored = versionMapper.selectExact(workflowId, version)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow version"));
        if (stored.getLifecycleState() != ToolLifecycleStateEnum.DRAFT) {
            throw new IllegalStateException("only draft workflow versions can be published");
        }
        WorkflowDefinition definition = toDefinition(stored);
        WorkflowValidationResult validation = validator.validate(definition, principal);
        if (!validation.valid()) {
            throw new IllegalArgumentException("invalid workflow: " + validation.issues());
        }
        CompiledWorkflow compiled = compiler.compile(definition);
        int changed = versionMapper.publishCompiled(stored.getId(), expectedRowVersion,
                codec.encodePlan(compiled), codec.encodePinnedTools(compiled.pinnedTools()),
                compiled.entryNodeId(), compiled.checksum(), LocalDateTime.now());
        if (changed != 1) throw new IllegalStateException("workflow version changed concurrently");
        if (workflowMapper.updateLatest(workflowId, ownerId, version) != 1) {
            throw new IllegalStateException("failed to update workflow latest version");
        }
        return compiled;
    }

    private WorkflowVersionPo toPo(WorkflowDefinition definition, CompiledWorkflow compiled,
                                   ToolLifecycleStateEnum state) {
        return new WorkflowVersionPo().setWorkflowId(definition.workflowId())
                .setVersion(definition.version()).setName(definition.name())
                .setDescription(definition.description())
                .setTags(definition.tags())
                .setInputSchema(codec.encodeSchema(definition.inputSchema()))
                .setOutputSchema(codec.encodeSchema(definition.outputSchema()))
                .setExecutionPolicy(codec.encodePolicy(definition.executionPolicy()))
                .setNodes(codec.encodeNodes(definition.nodes())).setEdges(codec.encodeEdges(definition.edges()))
                .setCompiledPlan(compiled == null ? null : codec.encodePlan(compiled))
                .setPinnedTools(compiled == null ? null : codec.encodePinnedTools(compiled.pinnedTools()))
                .setEntryNodeId(compiled == null ? null : compiled.entryNodeId())
                .setChecksum(compiled == null ? codec.checksum(definition) : compiled.checksum())
                .setLifecycleState(state).setRowVersion(0L);
    }

    private WorkflowDefinition toDefinition(WorkflowVersionPo po) {
        return new WorkflowDefinition(po.getWorkflowId(), po.getVersion(), po.getName(),
                po.getDescription(), codec.decodeSchema(po.getInputSchema()),
                codec.decodeSchema(po.getOutputSchema()), codec.decodeNodes(po.getNodes()),
                codec.decodeEdges(po.getEdges()), po.getTags(), codec.decodePolicy(po.getExecutionPolicy()));
    }

    private String lockKey(String ownerId, String workflowId) {
        return "workflow:" + ownerId + ":" + workflowId;
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
    }
}
