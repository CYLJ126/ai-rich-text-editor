package com.arte.ainew.pojo.control;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * 能力契约，即可执行能力描述符
 * <p>
 * 固定版本的操作契约，发现不授予权限。可执行能力必须登记输入输出 Schema；Schema 可映射受信 Java 类型。
 *
 * @param definition   本能力契约的固定版本引用，不能为空且类型必须为 capability，用于精确定位并核对契约版本。
 * @param kind         能力所属的操作类型，不能为空，用于区分生成、向量化、媒体、工具及远程应用的请求和结果契约。
 * @param inputSchema  输入结构的固定版本引用，非空时类型必须为 schema；可发现或停用状态允许为空，EXECUTABLE 状态必须提供。
 * @param outputSchema 输出结构的固定版本引用，非空时类型必须为 schema；可发现或停用状态允许为空，EXECUTABLE 状态必须提供。
 * @param features     能力声明的特性集合，可为空集合，保存为不含空元素的不可变集合；实际支持情况仍须由绑定、适配器及执行入口校验。
 * @param sideEffect   能力契约声明的副作用类别，不能为空，用于风险判断及执行策略；不表示本次执行已经产生的副作用事实。
 * @param availability 能力契约的可用状态，不能为空；EXECUTABLE 仅表示登记为可执行，实际调用仍须满足当前授权、绑定、适配及预算等条件。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public record CapabilityDescriptor(DefinitionRef definition, Kind kind, DefinitionRef inputSchema,
                                   DefinitionRef outputSchema, Set<Feature> features,
                                   SideEffect sideEffect, Availability availability) implements Serializable {

    /**
     * 能力的操作类型，用于选择相应请求、结果契约及处理链路；枚举存在不表示已接入可用实现。
     */
    public enum Kind {
        // 基于消息进行文本或多模态内容生成。
        GENERATION,
        // 将输入内容转换为向量表示。
        EMBEDDING,
        // 图像、音频、视频等媒体内容的生成或处理。
        MEDIA,
        // 按固定工具契约执行本地业务操作或远端工具调用。
        TOOL,
        // 调用远程应用或 Agent 服务，保留其专有会话、任务及结果语义。
        REMOTE_APPLICATION
    }

    /**
     * 能力声明支持的输入模态及执行特性，供发现与兼容性校验使用，不授予权限或承诺适配器已实现。
     */
    public enum Feature {
        // 支持文本输入
        TEXT_INPUT,
        // 支持图像输入
        IMAGE_INPUT,
        // 支持音频输入
        AUDIO_INPUT,
        // 支持视频输入
        VIDEO_INPUT,
        // 支持增量流式输出，不表示能够在中断后继续远端生成。
        STREAMING,
        // 支持模型提出工具调用请求，实际工具执行仍由运行时校验和调度。
        TOOL_CALLS,
        // 支持结构化输出，返回内容仍须通过相应 Schema 及领域校验。
        STRUCTURED_OUTPUT,
        // 支持返回执行用量信息，具体用量是否完整、可信仍由实际报告确定。
        USAGE_REPORTING,
        // 支持异步任务受理及后续处理，受理不等于任务完成。
        ASYNC_TASK,
        // 支持查询任务状态或结果。
        TASK_QUERY,
        // 支持请求取消任务，不表示能够撤回已经产生的副作用。
        CANCEL
    }

    /**
     * 操作契约声明的副作用类别，用于准入、审批及风险控制；实际发生情况由执行结果另行记录。
     */
    public enum SideEffect {
        // 只读操作，不修改业务资源；仍可能访问远端、外发资料或产生调用费用。
        READ_ONLY,
        // 会创建或修改业务资源，须校验相应写权限及版本。
        RESOURCE_WRITE,
        // 可能对外部系统产生实际影响，例如发送消息或提交外部操作。
        EXTERNAL_EFFECT,
        // 副作用类别尚未确认，不能据此视为只读或可安全重试。
        UNKNOWN
    }

    /**
     * 能力契约的登记可用状态，与单次执行状态及当前主体的使用权限独立。
     */
    public enum Availability {
        // 已发现或登记描述，尚不能仅凭此状态执行。
        DISCOVERED,
        // 登记为可执行，必须具备输入输出 Schema，调用时仍需完成当前资格及依赖校验。
        EXECUTABLE,
        // 已停用，不允许作为当前可执行能力使用。
        DISABLED
    }

    public CapabilityDescriptor {
        Objects.requireNonNull(definition, "definition").requireType("capability");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(availability, "availability");
        features = ContractChecks.set(features, "features", Feature.values().length);
        if (inputSchema != null) {
            inputSchema.requireType("schema");
        }
        if (outputSchema != null) {
            outputSchema.requireType("schema");
        }
        ContractChecks.require(availability != Availability.EXECUTABLE || inputSchema != null && outputSchema != null,
                "Executable capability requires input and output schemas");
    }
}
