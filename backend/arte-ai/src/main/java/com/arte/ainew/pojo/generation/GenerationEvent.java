package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.util.Objects;

/**
 * 适配层输出的生成增量，尚无平台耐久游标。Finished 不是 Invocation 成功，onComplete 也不是成功证明。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public sealed interface GenerationEvent extends Serializable permits GenerationEvent.TextDelta,
        GenerationEvent.ToolCallDelta, GenerationEvent.UsageReported, GenerationEvent.Finished {
    default long characterCount() {
        return 0;
    }

    record TextDelta(String text) implements GenerationEvent {
        public TextDelta {
            ContractChecks.text(text, "text", ContractChecks.MAX_TEXT_CHARS);
        }

        @Override
        public long characterCount() {
            return text.length();
        }
    }

    /**
     * 参数片段不是完整 JSON，不能执行；先按 index 聚合并解析成 ToolCall，再执行 Schema／授权校验。
     */
    record ToolCallDelta(int index, String callId, String toolName,
                         String argumentsFragment) implements GenerationEvent {
        public ToolCallDelta {
            ContractChecks.range(index, "index", 0, ContractChecks.MAX_TOOLS - 1);
            ContractChecks.optionalId(callId, "callId");
            ContractChecks.optionalId(toolName, "toolName");
            Objects.requireNonNull(argumentsFragment, "argumentsFragment");
            ContractChecks.range(argumentsFragment.length(), "argumentsFragment.length", 0, ContractChecks.MAX_TEXT_CHARS);
            ContractChecks.require(callId != null || toolName != null || !argumentsFragment.isEmpty(), "Empty tool delta");
        }

        @Override
        public long characterCount() {
            return argumentsFragment.length();
        }
    }

    record UsageReported(Usage usage) implements GenerationEvent {
        public UsageReported {
            Objects.requireNonNull(usage, "usage");
        }
    }

    record Finished(ModelResult.FinishReason reason) implements GenerationEvent {
        public Finished {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
