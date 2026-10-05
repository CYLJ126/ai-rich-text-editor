package com.arte.ainew.pojo.entry;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.generation.GenerationOptions;

import java.io.Serializable;
import java.util.Objects;

/**
 * 场景选择参数，不携带 owner、凭据或执行状态；当前 ExecutionContext 由可信入口另行提供。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public final class EntryRequests {
    private EntryRequests() {
    }

    /**
     * 首批文本聊天。parentTurnId 为追问路径；编辑重发使用 supersedesTurnId，不修改旧轮次。
     * profile 中的工具／输出格式只能由服务端发布配置解析，不接收可执行回调。
     */
    public record Chat(String conversationId, long expectedVersion, String parentTurnId, String supersedesTurnId,
                       ContextRequest context, DefinitionRef capability, DefinitionRef binding,
                       GenerationOptions generationOptions, ExecutionOptions options) implements Serializable {
        public Chat {
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.optionalId(parentTurnId, "parentTurnId");
            ContractChecks.optionalId(supersedesTurnId, "supersedesTurnId");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(capability, "capability").requireType("capability");
            Objects.requireNonNull(binding, "binding").requireType("binding");
            Objects.requireNonNull(generationOptions, "generationOptions");
            Objects.requireNonNull(options, "options");
            if (context.history() != null) {
                ContractChecks.require(conversationId.equals(context.history().conversationId())
                        && expectedVersion == context.history().conversationVersion(), "History selection mismatch");
            }
        }
    }

    /**
     * 使用原调用固定输入，新建 Invocation／幂等键，不能冒充原调用的自动重试。
     */
    public record Regenerate(String originalInvocationId, long expectedConversationVersion,
                             ExecutionOptions options) implements Serializable {
        public Regenerate {
            ContractChecks.id(originalInvocationId, "originalInvocationId");
            ContractChecks.range(expectedConversationVersion, "expectedConversationVersion", 0, Long.MAX_VALUE - 1);
            Objects.requireNonNull(options, "options");
        }
    }

    /**
     * action 必须是已发布固定版本；context=null 表示动作不选入额外上下文，不要求会话。
     */
    public record Action(DefinitionRef action, StructuredValue.ObjectValue input,
                         ContextRequest context, ExecutionOptions options) implements Serializable {
        public Action {
            Objects.requireNonNull(action, "action").requireType("action");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(options, "options");
        }
    }
}
