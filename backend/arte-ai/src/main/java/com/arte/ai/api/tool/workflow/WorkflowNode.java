package com.arte.ai.api.tool.workflow;

import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolReference;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工作流程节点的封闭模型。
 * <p>
 * 工具节点显式携带工具版本，其余扩展节点通过 configuration 保存类型配置。
 */
public sealed interface WorkflowNode permits WorkflowNode.StartNode, WorkflowNode.EndNode,
        WorkflowNode.ToolNode, WorkflowNode.ConfiguredNode {

    String nodeId();

    String name();

    WorkflowNodeTypeEnum type();

    Map<String, String> inputBindings();

    Set<String> outputNames();

    Map<String, Object> configuration();

    record StartNode(
            String nodeId,
            String name,
            Set<String> outputNames,
            Map<String, Object> configuration
    ) implements WorkflowNode {

        public StartNode {
            requireIdentity(nodeId, name);
            outputNames = copySet(outputNames);
            configuration = copyMap(configuration);
        }

        @Override
        public WorkflowNodeTypeEnum type() {
            return WorkflowNodeTypeEnum.START;
        }

        @Override
        public Map<String, String> inputBindings() {
            return Map.of();
        }

    }

    record EndNode(
            String nodeId,
            String name,
            Map<String, String> inputBindings,
            Map<String, Object> configuration
    ) implements WorkflowNode {

        public EndNode {
            requireIdentity(nodeId, name);
            inputBindings = copyBindings(inputBindings);
            configuration = copyMap(configuration);
        }

        @Override
        public WorkflowNodeTypeEnum type() {
            return WorkflowNodeTypeEnum.END;
        }

        @Override
        public Set<String> outputNames() {
            return Set.of();
        }

    }

    record ToolNode(
            String nodeId,
            String name,
            ToolReference tool,
            Map<String, String> inputBindings,
            Set<String> outputNames,
            Map<String, Object> configuration,
            ToolExecutionPolicy executionPolicy
    ) implements WorkflowNode {

        public ToolNode {
            requireIdentity(nodeId, name);
            Objects.requireNonNull(tool, "tool must not be null");
            inputBindings = copyBindings(inputBindings);
            outputNames = copySet(outputNames);
            configuration = copyMap(configuration);
        }

        @Override
        public WorkflowNodeTypeEnum type() {
            return WorkflowNodeTypeEnum.TOOL;
        }
    }

    record ConfiguredNode(
            String nodeId,
            String name,
            WorkflowNodeTypeEnum type,
            Map<String, String> inputBindings,
            Set<String> outputNames,
            Map<String, Object> configuration,
            ToolExecutionPolicy executionPolicy
    ) implements WorkflowNode {

        public ConfiguredNode {
            requireIdentity(nodeId, name);
            Objects.requireNonNull(type, "type must not be null");
            if (type == WorkflowNodeTypeEnum.START || type == WorkflowNodeTypeEnum.END
                    || type == WorkflowNodeTypeEnum.TOOL) {
                throw new IllegalArgumentException("START, END and TOOL require their dedicated node type");
            }
            inputBindings = copyBindings(inputBindings);
            outputNames = copySet(outputNames);
            configuration = copyMap(configuration);
        }
    }

    private static void requireIdentity(String nodeId, String name) {
        requireText(nodeId, "nodeId");
        requireText(name, "name");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static Map<String, String> copyBindings(Map<String, String> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    private static Set<String> copySet(Set<String> value) {
        return value == null ? Set.of() : Set.copyOf(value);
    }
}
