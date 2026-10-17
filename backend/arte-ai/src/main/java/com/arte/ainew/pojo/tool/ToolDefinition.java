package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 固定版本的工具产品契约，不持有执行器、连接、凭据值或运行身份。
 * 构造器只校验结构与风险／策略一致性；Schema 内容、配置合法性、校验和真实性及当前授权另行验证。
 *
 * @param definition           type=tool，稳定 ID 使用 namespace.name，版本由 DefinitionRef 固定。
 * @param providerId           受管定义来源，不表示进程内已有真实执行实现。
 * @param capability           对应的通用能力固定引用；Schema、特性和副作用由本对象单向投影。
 * @param inputSchema          输入 Schema 固定引用，内容由独立校验器验证。
 * @param outputSchema         输出 Schema 固定引用。
 * @param configurationSchema  非敏感配置的独立 Schema 固定引用。
 * @param defaultConfiguration 默认非敏感配置；使用有界、深度不可变的 JSON 值对象。
 * @param checksum             发布契约的 SHA-256 摘要声明；构造成功不证明与实际内容匹配。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public record ToolDefinition(DefinitionRef definition, String providerId, String title, String description,
                             Set<String> tags, DefinitionRef capability, DefinitionRef inputSchema,
                             DefinitionRef outputSchema, DefinitionRef configurationSchema,
                             StructuredValue.ObjectValue defaultConfiguration, ToolCapabilities capabilities,
                             ToolRiskProfile risk, ToolExecutionPolicy defaultPolicy,
                             String checksum) implements Serializable {
    private static final Pattern TOOL_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z][A-Za-z0-9_-]*)+");

    public ToolDefinition {
        Objects.requireNonNull(definition, "definition").requireType("tool");
        ContractChecks.require(TOOL_ID.matcher(definition.id()).matches(), "Tool identity must use namespace.name");
        ContractChecks.id(providerId, "providerId");
        ContractChecks.text(title, "title", 200);
        ContractChecks.text(description, "description", 4096);
        tags = ContractChecks.set(tags, "tags", 32);
        tags.forEach(tag -> ContractChecks.text(tag, "tag", 64));
        Objects.requireNonNull(capability, "capability").requireType("capability");
        Objects.requireNonNull(inputSchema, "inputSchema").requireType("schema");
        Objects.requireNonNull(outputSchema, "outputSchema").requireType("schema");
        Objects.requireNonNull(configurationSchema, "configurationSchema").requireType("schema");
        Objects.requireNonNull(defaultConfiguration, "defaultConfiguration");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        ContractChecks.require(defaultPolicy.approvalMode().satisfies(risk.minimumApprovalMode()),
                "Tool policy cannot weaken required risk approval");
        ContractChecks.digest(checksum, "checksum");
    }

    /**
     * 从同一份 Schema／风险／能力信息派生发现描述，不因值对象存在而声明 EXECUTABLE。
     * 发布及绑定解析阶段仍须核实实现、依赖、风险和当前使用资格。
     */
    public CapabilityDescriptor discoveredCapability() {
        return new CapabilityDescriptor(capability, CapabilityDescriptor.Kind.TOOL, inputSchema, outputSchema,
                capabilities.features(), risk.sideEffect(), CapabilityDescriptor.Availability.DISCOVERED);
    }
}
