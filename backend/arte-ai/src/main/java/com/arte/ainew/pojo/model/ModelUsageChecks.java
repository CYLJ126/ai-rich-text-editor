package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;

/**
 * 指定执行空间及固定版本的受权检查，不返回预算账户或凭据标识。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 15:50 ✾
 */
public record ModelUsageChecks(Scope scope, ResourceRef resource, DefinitionRef binding,
                               List<Check> spaceConditions, List<Check> personalConditions,
                               List<String> allowedActions) {

    public ModelUsageChecks {
        spaceConditions = List.copyOf(spaceConditions);
        personalConditions = List.copyOf(personalConditions);
        allowedActions = List.copyOf(allowedActions);
    }

    public enum Condition {
        PUBLICATION, RUNTIME_ADAPTER, EXECUTION_POLICY, WORKSPACE_SELECTION, SPACE_BUDGET_RULE,
        WORKSPACE_AI_ACCESS, MODEL_ACCESS, CREDENTIAL_USE, OWN_BUDGET
    }

    public enum Status {PASS, BLOCKED, UNKNOWN, NOT_APPLICABLE}

    public record Check(Condition condition, Status status, String reason) {
        public static Check evaluated(Condition condition, String blocker) {
            return blocker == null ? new Check(condition, Status.PASS, "VERIFIED")
                    : new Check(condition, Status.BLOCKED, blocker);
        }

        public static Check unavailable(Condition condition, boolean applicable) {
            return applicable ? new Check(condition, Status.UNKNOWN, "PREREQUISITE_UNVERIFIED")
                    : new Check(condition, Status.NOT_APPLICABLE, "RESOURCE_PUBLICATION_REQUIRED");
        }
    }
}
