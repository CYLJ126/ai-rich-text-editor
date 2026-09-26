package com.arte.ai.pojo.tool;

import java.util.List;
import java.util.Objects;

/**
 * 工作流程静态校验结果。valid 由问题级别派生，不能与 issues 不一致。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record WorkflowValidationResult(List<Issue> issues) {

    public WorkflowValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue -> issue.severity() == Issue.Severity.ERROR);
    }

    public record Issue(String code, Severity severity, String nodeId, String message) {

        public enum Severity {
            INFO,
            WARNING,
            ERROR
        }

        public Issue {
            requireText(code, "code");
            Objects.requireNonNull(severity, "severity must not be null");
            requireText(message, "message");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
