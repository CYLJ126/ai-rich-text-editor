package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 带类型的回答候选引用；Run 与 Invocation 分别查询，不以字符串 ID 猜测执行类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public record AnswerCandidate(Kind kind, String executionId) implements Serializable {
    public enum Kind {INVOCATION, TOOL_CHAT_RUN}

    public AnswerCandidate {
        Objects.requireNonNull(kind);
        ContractChecks.id(executionId, "candidate executionId");
    }

    public static AnswerCandidate invocation(String id) {
        return new AnswerCandidate(Kind.INVOCATION, id);
    }

    public static AnswerCandidate run(String id) {
        return new AnswerCandidate(Kind.TOOL_CHAT_RUN, id);
    }
}
