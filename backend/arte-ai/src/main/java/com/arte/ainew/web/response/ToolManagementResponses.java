package com.arte.ainew.web.response;

import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.web.ToolManagementValues;
import com.arte.ainew.web.request.ToolManagementRequests;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TOOL 公开响应的显式投影；不返回执行器定位、内部快照信封、临时授权或审核原文。
 * 当前绑定与版本字段不直接序列化内部对象，配置和 Schema 均转换为标准 JSON。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class ToolManagementResponses {

    private ToolManagementResponses() {
    }

    public record Page<T>(List<T> records, String nextCursor, int scanned, boolean exhausted) {
    }

    public record Release(DefinitionRef tool, ToolRelease.State state, String version, Instant existingBindingsUntil,
                          Instant changedAt, boolean permitsNewBinding, DefinitionRef compatibleFrom) {
    }

    public record Risk(com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect sideEffect, boolean destructive,
                       ToolRiskProfile.Idempotency idempotency, Set<String> requiredScopes,
                       List<String> networkOrigins) {
    }

    public record Tool(DefinitionRef tool, DefinitionRef provider, String title, String description, Set<String> tags,
                       Set<com.arte.ainew.pojo.control.CapabilityDescriptor.Feature> features,
                       boolean outcomeQuerySupported,
                       Risk risk, ToolManagementRequests.Policy defaultPolicy, Release release, String checksum) {
    }

    public record Schema(DefinitionRef definition, Object document, String documentJson, String checksum) {
    }

    public record ToolDetail(Tool tool, Schema inputSchema, Schema outputSchema, Schema configurationSchema,
                             Object defaultConfiguration) {
    }

    public record Provider(DefinitionRef provider, ToolProviderDefinition.Kind kind) {
    }

    public record DiscoveryCommand(String delivery, String commandId, String result, String checksum, int providers,
                                   int schemas, int tools,
                                   Instant recordedAt, String nodeState, boolean nodeDiscoveryHealthy,
                                   String nodeChecksum) {
    }

    public record Binding(String bindingId, String toolId, String version, String name, boolean enabled,
                          Instant createdAt, Instant updatedAt) {
    }

    public record Settings(DefinitionRef tool, ToolBindingSettings.VersionPolicy versionPolicy, String name,
                           boolean enabled, Object configuration,
                           String configurationJson, Map<String, DefinitionRef> secrets,
                           ToolManagementRequests.Policy policy,
                           List<String> networkOrigins) {
    }

    public record Revision(DefinitionRef revision, Settings settings, Instant createdAt) {
    }

    public record BindingDetail(Binding binding, Revision revision) {
    }

    public record Plan(DefinitionRef binding, DefinitionRef revision, DefinitionRef tool, String contractDigest,
                       Object configuration, String configurationJson, String configurationDigest,
                       ToolManagementRequests.Policy policy,
                       List<String> networkOrigins) {
    }

    public record Upgrade(String bindingId, String expectedVersion, DefinitionRef currentBaseline,
                          DefinitionRef currentTool,
                          boolean compatible, List<ToolBindingUpgradePreview.Change> changes, boolean valid,
                          String rejection,
                          Plan proposed, String digest) {
    }

    public record Validation(boolean valid, String contractDigest, String discoveryChecksum,
                             List<ToolPublicationValidation.Issue> issues, Instant checkedAt) {
    }

    /**
     * delivery 是回执首次／重放；outcome／rejection 是首次原始决定。
     */
    public record BindingCommand(String delivery, String commandId, String bindingId, String outcome, String rejection,
                                 String currentVersion, Binding binding, Instant recordedAt) {
    }

    public record ReleaseCommand(String delivery, String commandId, String outcome, String rejection, Release release,
                                 Instant recordedAt) {
    }

    public record BindingAudit(BindingCommand receipt, String actorSubjectId, String actorKind, String action,
                               String reason) {
    }

    public record ReleaseAudit(ReleaseCommand receipt, String actorSubjectId, String actorKind, String action,
                               String reason) {
    }

    public static <T, R> Page<R> page(ToolManagementService.Page<T> page, java.util.function.Function<T, R> transform) {
        return new Page<>(page.records().stream().map(transform).toList(), page.nextCursor(), page.scanned(), page.nextCursor() == null);
    }

    public static Release release(ToolRelease value) {
        return value == null ? null : new Release(value.tool(), value.state(), Long.toString(value.version()), value.existingBindingsUntil(), value.changedAt(),
                value.permitsNewBinding(), value.publication() == null ? null : value.publication().review().compatibleFrom());
    }

    public static Tool tool(ToolManagementService.ToolView value) {
        var tool = value.definition().tool();
        var risk = tool.risk();
        return new Tool(tool.definition(), value.definition().provider(), tool.title(), tool.description(), tool.tags(), tool.capabilities().features(),
                tool.capabilities().outcomeQuerySupported(), new Risk(risk.sideEffect(), risk.destructive(), risk.idempotency(), risk.requiredScopes(),
                origins(risk.allowedNetworkOrigins())), policy(tool.defaultPolicy()), release(value.release()), tool.checksum());
    }

    public static ToolDetail detail(ToolManagementService.ToolDetail value, boolean managed) {
        var tool = value.tool().definition().tool();
        return new ToolDetail(tool(value.tool()), schema(value.schemas().get(tool.inputSchema())), schema(value.schemas().get(tool.outputSchema())),
                schema(value.schemas().get(tool.configurationSchema())), managed ? ToolManagementValues.json(tool.defaultConfiguration()) : null);
    }

    private static Schema schema(ToolSchemaDefinition value) {
        return new Schema(value.definition(), ToolManagementValues.json(value.document()), CanonicalJson.encode(ToolManagementValues.json(value.document())), value.checksum());
    }

    public static Provider provider(ToolProviderDefinition value) {
        return new Provider(value.definition(), value.kind());
    }

    public static DiscoveryCommand discovery(ToolManagementService.Refresh value) {
        var receipt = value.receipt().value();
        var node = value.node();
        return new DiscoveryCommand(value.receipt().code().name(), receipt == null ? null : receipt.commandId(), receipt == null ? value.receipt().code().name() : receipt.result().name(),
                receipt == null ? null : receipt.checksum(), receipt == null ? 0 : receipt.providers(), receipt == null ? 0 : receipt.schemas(), receipt == null ? 0 : receipt.tools(),
                receipt == null ? null : receipt.recordedAt(), node.lastResult().name(), node.discoveryHealthy(), node.snapshot() == null ? null : node.snapshot().checksum());
    }

    public static Binding binding(ToolBindingDefinition value) {
        return value == null ? null : new Binding(value.bindingId(), value.toolId(), Long.toString(value.version()), value.name(), value.enabled(), value.createdAt(), value.updatedAt());
    }

    public static Revision revision(ToolBindingRevision value) {
        return new Revision(value.definition(), settings(value.settings()), value.createdAt());
    }

    public static BindingDetail detail(ToolManagementService.BindingView value) {
        return new BindingDetail(binding(value.definition()), revision(value.revision()));
    }

    public static Settings settings(ToolBindingSettings value) {
        return new Settings(value.tool(), value.versionPolicy(), value.name(), value.enabled(), ToolManagementValues.json(value.configuration()),
                CanonicalJson.encode(ToolManagementValues.json(value.configuration())), value.secrets(), policy(value.policy()), value.networkOrigins() == null ? null : origins(value.networkOrigins()));
    }

    public static ToolManagementRequests.Policy policy(ToolExecutionPolicy value) {
        return value == null ? null : new ToolManagementRequests.Policy(value.timeout().toMillis(), value.maxAttempts(),
                value.retryBackoff().toMillis(), value.maxInputBytes(), value.maxOutputBytes(), value.approvalMode());
    }

    private static List<String> origins(Set<java.net.URI> origins) {
        return origins.stream().map(Object::toString).sorted().toList();
    }

    public static Validation validation(ToolPublicationValidation value) {
        return new Validation(value.valid(), value.contractDigest(), value.discoveryChecksum(), value.issues(), value.checkedAt());
    }

    public static Upgrade upgrade(ToolBindingUpgradePreview value) {
        var result = value.proposed();
        var p = result.value();
        var plan = p == null ? null : new Plan(p.definition(), p.revision(), p.tool().tool().definition(), p.contractDigest(), ToolManagementValues.json(p.effectiveConfiguration()), CanonicalJson.encode(ToolManagementValues.json(p.effectiveConfiguration())),
                p.configurationDigest(), policy(p.effectivePolicy()), origins(p.networkOrigins()));
        return new Upgrade(value.bindingId(), Long.toString(value.expectedVersion()), value.currentBaseline(), value.currentTool(), value.compatible(), value.changes(),
                result.successful(), result.rejection().name(), plan, value.digest());
    }

    public static BindingCommand bindingCommand(StoreOutcome<ToolBindingReceipt> value) {
        return value.value() == null ? new BindingCommand(value.code().name(), null, null, value.code().name(), value.code().name(), null, null, null)
                : bindingCommand(value.code().name(), value.value());
    }

    private static BindingCommand bindingCommand(String delivery, ToolBindingReceipt value) {
        var result = value.result();
        String rejection = result.rejection() == ToolBindingResult.Rejection.OWNER_MISMATCH ? "NOT_FOUND" : result.rejection().name();
        String outcome = result.rejection() == ToolBindingResult.Rejection.OWNER_MISMATCH ? "NOT_FOUND" : result.outcome().name();
        return new BindingCommand(delivery, value.commandId(), value.bindingId(), outcome, rejection, result.currentVersion() == null ? null : result.currentVersion().toString(), binding(result.value()), value.recordedAt());
    }

    public static ReleaseCommand releaseCommand(StoreOutcome<ToolReleaseReceipt> value) {
        return value.value() == null ? new ReleaseCommand(value.code().name(), null, value.code().name(), value.code().name(), null, null)
                : releaseCommand(value.code().name(), value.value());
    }

    private static ReleaseCommand releaseCommand(String delivery, ToolReleaseReceipt value) {
        return new ReleaseCommand(delivery, value.commandId(), value.outcome().name(), value.rejection().name(), release(value.release()), value.recordedAt());
    }

    public static BindingAudit bindingAudit(ToolBindingReceipt value) {
        return new BindingAudit(bindingCommand("RECORDED", value), value.actorSubjectId(), value.actorKind().name(), value.command().action().name(), value.command().reason());
    }

    public static ReleaseAudit releaseAudit(ToolReleaseReceipt value) {
        return new ReleaseAudit(releaseCommand("RECORDED", value), value.actorSubjectId(), value.actorKind().name(), value.command().action().name(), value.command().reason());
    }
}
