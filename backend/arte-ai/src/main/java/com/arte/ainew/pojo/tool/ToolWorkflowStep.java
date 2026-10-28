package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.util.Objects;

/**
 * 工作流须在首次提交前耐久保存的工具步骤意图
 * <p>
 * 调度重试沿用原 key 和 input。
 * 标识某个流程实例中的一次工具操作，固定参数和操作身份。
 * 循环的不同轮次使用不同 operationId，不能用 Worker、租约或调度 attempt 充当操作身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 13:40 ✾
 */
public record ToolWorkflowStep(Key key, ToolAdmissionInput input) {

    public ToolWorkflowStep {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(input, "input");
    }

    /**
     * 仅关联逻辑步骤，不授予工作流或 TOOL 权限；归属取自当前可信 ExecutionContext。
     */
    public record Key(String workflowRunId, String nodeId, String operationId) {
        public Key {
            ContractChecks.id(workflowRunId, "workflowRunId");
            ContractChecks.id(nodeId, "nodeId");
            ContractChecks.id(operationId, "operationId");
        }

        /**
         * v1 长度前缀哈希固定 Invocation 身份；不包含绑定、参数、期限或调度次数，意图变化只能报冲突。
         * 此算法是耐久契约，后续升级仍须支持已保存身份。
         */
        public String invocationId(ExecutionOwner owner) {
            Objects.requireNonNull(owner, "owner");
            return "workflow-tool-" + CanonicalJson.key("workflow-tool-invocation-v1", owner.tenantId(), owner.workspaceId(),
                    owner.subjectId(), workflowRunId, nodeId, operationId);
        }

        public String idempotencyKey(ExecutionOwner owner) {
            return "workflow-tool-admission-" + invocationId(owner);
        }
    }
}
