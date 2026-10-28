package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 类型化生成输入
 * <p>
 * 封装一次模型生成的消息、生成参数、工具描述及输出格式。
 * <p>
 * 固定实际消息、生成选项、允许工具描述及输出格式；工具描述不携带可执行回调。
 * 发布配置及能力校验决定可用模态／特性，不默默忽略参数；消息角色、来源与工具响应须由可信组装器核对。
 * 作为 InvocationRequest 的 GENERATION 类型输入，由提供商适配器转换为远端请求，不保存调用状态或执行结果。
 *
 * @param messages     本次生成实际使用的有序消息列表，至少一条，消息 ID 不重复，保存为不可变列表；可包含已组装的指令、用户输入、历史及工具响应，角色与响应关联仍须校验。
 * @param options      模型生成参数，不能为空，包含输出 Token 上限、采样参数及停止序列；与控制期限、重试和字节上限的 ExecutionOptions 分别校验。
 * @param tools        本次允许模型提出调用的工具描述列表，可为空，固定工具引用及工具 ID 均不得重复；只声明候选工具，实际调用仍须经过参数校验、当前授权及运行时调度。
 * @param outputFormat 本次要求的生成内容格式，不能为空，使用 TextOutput 或 StructuredOutput 表达；能力与适配器须支持所选格式，不能仅因契约可构造就视为可执行。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:18 ✾
 */
public record GenerationRequest(List<ChatMessage> messages, GenerationOptions options,
                                List<ToolDefinition> tools, OutputFormat outputFormat) implements CapabilityInput {

    /**
     * 输出格式契约接口
     * <p>
     * 统一表达文本或结构化输出要求，供能力校验和适配器使用。
     * 生成内容的输出格式契约，通过封闭类型区分普通文本与受固定 Schema 约束的结构化内容。
     * 供能力校验和提供商适配器选择请求映射及输出校验方式，不表示同步或流式传输方式，也不描述执行终态。
     * 当前固定文本控制面及 DeepSeek 生成适配器仅接受 TextOutput，StructuredOutput 保留类型契约。
     */
    public sealed interface OutputFormat extends Serializable permits TextOutput, StructuredOutput {
    }

    /**
     * 普通文本输出的格式标记
     * <p>
     * 无字段，不额外指定生成内容的结构化 Schema。
     * 是否允许模型同时提出工具调用由 tools、能力特性及执行约束决定，不由此标记授予工具执行资格。
     */
    public record TextOutput() implements OutputFormat {
    }

    /**
     * 要求模型生成符合固定 Schema 的结构化内容，用于声明结果内容的结构约束。
     * 该 Schema 约束生成内容，不替代 ModelResult 等平台结果封装契约；实际支持及返回内容仍须由执行链路校验。
     * 当前固定文本控制面及 DeepSeek 生成适配器尚不支持此格式。
     *
     * @param schema 生成内容须遵循的固定版本 Schema 引用，不能为空且类型必须为 schema；不内嵌 Schema 正文，解析及内容校验由受信服务完成。
     */
    public record StructuredOutput(DefinitionRef schema) implements OutputFormat {

        public StructuredOutput {
            Objects.requireNonNull(schema, "schema").requireType("schema");
        }
    }

    /**
     * 提供给模型的单个工具描述
     * <p>
     * 供模型理解用途并提出类型化调用请求。
     * 由受信工具材料投影得到，不携带连接、凭据、执行回调或完整管理配置，也不因模型选中就自动执行。
     *
     * @param tool        本次允许使用的固定版本工具引用，不能为空且类型必须为 tool；模型提出的调用须解析回本次固定工具集合。
     * @param description 工具用途及使用说明，不能为空白且长度不超过 4096 个 UTF-16 单元，用于向模型解释工具，不作为授权依据。
     * @param inputSchema 工具参数的固定版本 Schema 引用，不能为空且类型必须为 schema；适配时解析受信定义，执行前仍须校验实际参数。
     * @param sideEffect  工具契约声明的副作用类别，不能为空，用于表达只读、资源写入、外部影响或未知风险，不表示本次调用已产生的实际副作用。
     */
    public record ToolDefinition(DefinitionRef tool, String description, DefinitionRef inputSchema,
                                 CapabilityDescriptor.SideEffect sideEffect) implements Serializable {
        public ToolDefinition {
            Objects.requireNonNull(tool, "tool").requireType("tool");
            ContractChecks.text(description, "description", 4096);
            Objects.requireNonNull(inputSchema, "inputSchema").requireType("schema");
            Objects.requireNonNull(sideEffect, "sideEffect");
        }
    }

    public GenerationRequest {
        messages = ContractChecks.list(messages, "messages", 1, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(messages.stream().map(ChatMessage::messageId).toList(), "message IDs");
        ContractChecks.require(messages.stream().mapToLong(ChatMessage::characterCount).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Generation input exceeds character limit");
        Objects.requireNonNull(options, "options");
        tools = ContractChecks.list(tools, "tools", 0, ContractChecks.MAX_TOOLS);
        ContractChecks.unique(tools.stream().map(ToolDefinition::tool).toList(), "tools");
        ContractChecks.unique(tools.stream().map(tool -> tool.tool().id()).toList(), "tool names");
        ContractChecks.require(messages.stream().mapToLong(ChatMessage::characterCount).sum()
                        + tools.stream().mapToLong(tool -> tool.description().length()).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Messages and tool descriptions exceed input character limit");
        Objects.requireNonNull(outputFormat, "outputFormat");
    }

    @Override
    public CapabilityDescriptor.Kind kind() {
        return CapabilityDescriptor.Kind.GENERATION;
    }
}
