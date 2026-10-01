package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.mapper.AssistantMapper;
import com.arte.ai.mapper.tool.AssistantToolMapper;
import com.arte.ai.pojo.BaseDto;
import com.arte.ai.pojo.assistant.AssistantDto;
import com.arte.ai.pojo.assistant.AssistantPo;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.AssistantToolPo;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import com.arte.core.enums.StatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认 AI 助手工具配置服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Service
@RequiredArgsConstructor
public class DefaultAssistantToolManager implements AssistantToolManager {

    private final AssistantMapper assistantMapper;
    private final AssistantToolMapper assistantToolMapper;
    private final ToolBindingManager bindingManager;
    private final ToolPolicyMerger policyMerger;
    private final ToolRegistry registry;
    private final ToolDistributedLockExecutor lockExecutor;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void replace(String ownerId, String workspaceId, Integer assistantId,
                        List<AssistantToolCommand> tools) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        requireAssistant(normalizedOwner, assistantId);
        List<AssistantToolCommand> commands = tools == null ? List.of() : List.copyOf(tools);
        validateCommands(normalizedOwner, workspaceId, commands);

        lockExecutor.execute("assistant:" + assistantId, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    requireAssistant(normalizedOwner, assistantId);
                    assistantToolMapper.deleteByAssistantId(assistantId);
                    for (AssistantToolCommand command : commands) {
                        AssistantToolPo association = new AssistantToolPo()
                                .setAssistantId(assistantId)
                                .setBindingId(command.bindingId())
                                .setEnabled(command.enabled())
                                .setPolicyOverride(policyMerger.encodeOverride(command.policyOverride()))
                                .setSortOrder(command.sortOrder());
                        association.setCreateBy(normalizedOwner);
                        association.setUpdateBy(normalizedOwner);
                        assistantToolMapper.insert(association);
                    }
                }));
    }

    @Override
    public List<ResolvedAssistantTool> resolveForModel(String ownerId, String workspaceId,
                                                       Integer assistantId) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        requireAssistant(normalizedOwner, assistantId);
        List<ResolvedAssistantTool> result = new ArrayList<>();
        Set<String> modelNames = new HashSet<>();

        for (AssistantToolPo association : assistantToolMapper.selectEnabledByAssistantId(assistantId)) {
            ResolvedToolBinding binding = bindingManager.resolve(normalizedOwner, workspaceId,
                    association.getBindingId()).orElse(null);
            if (binding == null) continue;
            ToolDefinition definition = registry.resolve(binding.tool()).map(Tool::getDefinition).orElse(null);
            if (definition == null) continue;
            String modelName = modelToolName(definition);
            if (!modelNames.add(modelName)) {
                throw new IllegalStateException("assistant exposes duplicate model tool name: " + modelName);
            }
            ToolExecutionPolicy effectivePolicy = policyMerger.tighten(binding.effectivePolicy(),
                    policyMerger.decodeOverride(association.getPolicyOverride()));
            org.springframework.ai.tool.definition.ToolDefinition modelDefinition =
                    new DefaultToolDefinition(modelName, definition.description(), definition.inputSchema().schema());
            result.add(new ResolvedAssistantTool(modelName, binding.tool(), binding.bindingId(),
                    association.getSortOrder(), modelDefinition, binding.effectiveConfiguration(),
                    binding.credentialReference(), effectivePolicy));
        }
        return List.copyOf(result);
    }

    @Override
    public List<AssistantToolCommand> listConfiguration(String ownerId, Integer assistantId) {
        requireAssistant(requireText(ownerId, "ownerId"), assistantId);
        return assistantToolMapper.selectByAssistantId(assistantId).stream()
                .map(association -> new AssistantToolCommand(association.getBindingId(),
                        Boolean.TRUE.equals(association.getEnabled()), association.getSortOrder(),
                        policyMerger.decodeOverride(association.getPolicyOverride())))
                .toList();
    }

    @Override
    public List<AssistantToolOptionView> listAssistants(String ownerId) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        QueryWrapper<AssistantDto> query = new QueryWrapper<>();
        query.and(scope -> scope.eq(BaseDto.COL_CREATE_BY, normalizedOwner)
                        .or().isNull(BaseDto.COL_CREATE_BY)
                        .or().eq(BaseDto.COL_CREATE_BY, ""))
                .orderByAsc(AssistantPo.COL_SORT_ORDER)
                .orderByDesc(BaseDto.COL_UPDATE_TIME);
        return assistantMapper.selectList(query).stream()
                .map(assistant -> new AssistantToolOptionView(assistant.getId(), assistant.getName(),
                        assistant.getDescription(), assistant.getAvatar(),
                        StatusEnum.isNormal(assistant.getStatus())))
                .toList();
    }

    private void validateCommands(String ownerId, String workspaceId,
                                  List<AssistantToolCommand> commands) {
        Set<String> bindings = new HashSet<>();
        Set<String> modelNames = new HashSet<>();
        for (AssistantToolCommand command : commands) {
            if (!bindings.add(command.bindingId())) {
                throw new IllegalArgumentException("duplicate assistant binding: " + command.bindingId());
            }
            ResolvedToolBinding binding = bindingManager.resolve(ownerId, workspaceId, command.bindingId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "binding is disabled, unavailable or outside the requested workspace: "
                                    + command.bindingId()));
            policyMerger.tighten(binding.effectivePolicy(), command.policyOverride());
            String modelName = modelToolName(binding.tool().namespace(), binding.tool().name());
            if (command.enabled() && !modelNames.add(modelName)) {
                throw new IllegalArgumentException("assistant cannot enable multiple versions of model tool: "
                        + modelName);
            }
        }
    }

    private AssistantDto requireAssistant(String ownerId, Integer assistantId) {
        if (assistantId == null) {
            throw new IllegalArgumentException("assistantId must not be null");
        }
        AssistantDto assistant = assistantMapper.selectById(assistantId);
        if (assistant == null) {
            throw new IllegalArgumentException("unknown assistant: " + assistantId);
        }
        if (assistant.getCreateBy() != null && !assistant.getCreateBy().isBlank()
                && !ownerId.equals(assistant.getCreateBy())) {
            throw new SecurityException("assistant does not belong to the current user");
        }
        return assistant;
    }

    private String modelToolName(ToolDefinition definition) {
        return modelToolName(definition.reference().namespace(), definition.reference().name());
    }

    private String modelToolName(String namespace, String name) {
        return (namespace + "__" + name).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

}
