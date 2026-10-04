package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 用量事实与估算分开；null 是未获知的单项，0 是已知为零。费用由预算账本另行对账。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Usage(Basis basis, Long inputTokens, Long outputTokens, Long totalTokens) implements Serializable {
    public enum Basis {UNKNOWN, ESTIMATED, PROVIDER_REPORTED}

    public Usage {
        Objects.requireNonNull(basis, "basis");
        for (Long value : new Long[]{inputTokens, outputTokens, totalTokens}) {
            if (value != null) {
                ContractChecks.range(value, "tokens", 0, Long.MAX_VALUE);
            }
        }
        boolean empty = inputTokens == null && outputTokens == null && totalTokens == null;
        ContractChecks.require((basis == Basis.UNKNOWN) == empty, "Usage basis does not match available values");
        if (inputTokens != null && outputTokens != null && totalTokens != null) {
            ContractChecks.require(inputTokens <= Long.MAX_VALUE - outputTokens
                    && totalTokens == inputTokens + outputTokens, "Token total must match known components");
        }
    }

    public static Usage unknown() {
        return new Usage(Basis.UNKNOWN, null, null, null);
    }
}
