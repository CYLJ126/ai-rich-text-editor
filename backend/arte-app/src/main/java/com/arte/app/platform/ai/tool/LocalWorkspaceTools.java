package com.arte.app.platform.ai.tool;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.StaticToolDefinitionSource;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import com.arte.app.platform.ai.PlatformToolManagementAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.utils.platform.PlatformChecks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.io.Serial;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_UPDATE;

/**
 * 本地工具：当前工作空间改名与说明更新
 * <p>
 * 复用领域命令、当前权限、乐观版本、审计及同事务操作回执。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:48 ✾
 */
public final class LocalWorkspaceTools implements ToolUsagePolicy {

    public static final DefinitionRef TOOL = ref("tool", "workspace.updateDetails");
    public static final DefinitionRef ADAPTER = ref("tool-adapter", "arte-workspace-details");
    private static final StructuredValue.ObjectValue EMPTY = object(Map.of());
    private final LocalToolWriteProperties properties;
    private final WorkspaceService workspaces;
    private final PlatformToolManagementAccess platform;
    private final RbacIdentityProvider identities;
    private final AuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;
    private final PublicTransactionRunner transactions;
    private final ObjectProvider<ToolApprovalStore> approvals;
    private final ExecutionRecordCodec codec;
    private final Clock clock;
    private final ToolProviderDefinition provider = new ToolProviderDefinition(ref("tool-provider", "arte.local-workspace"),
            ToolProviderDefinition.Kind.LOCAL, ref("tool-provider-adapter", "arte-workspace-source"), null);
    private final ToolSchemaDefinition input = schema("arte-workspace-details-input", Map.of(
            "expectedVersion", field("string", "当前工作空间版本", Map.of("minLength", number(1), "maxLength", number(19), "description", text("非负十进制整数文本，不含前导零；从工作空间详情读取当前版本。"))),
            "name", field("string", "新的工作空间名称", Map.of("minLength", number(1), "maxLength", number(100))),
            "description", field("string", "新的工作空间说明（空字符串表示清空）", Map.of("maxLength", number(1000)))));
    private final ToolSchemaDefinition output = schema("arte-workspace-details-output", Map.of(
            "workspaceId", field("string", "工作空间编号", Map.of()), "name", field("string", "已保存名称", Map.of()),
            "version", field("string", "已保存版本", Map.of()), "operationId", field("string", "领域操作编号", Map.of())));
    private final ToolSchemaDefinition configuration = schema("arte-workspace-details-configuration", Map.of());
    private final ToolDefinitionRevision revision;

    public LocalWorkspaceTools(LocalToolWriteProperties properties, WorkspaceService workspaces, PlatformToolManagementAccess platform,
                               RbacIdentityProvider identities, AuthorizationService authorization, WorkspaceGovernanceLocks locks,
                               PublicTransactionRunner transactions, ObjectProvider<ToolApprovalStore> approvals, ExecutionRecordCodec codec, Clock clock) {
        this.properties = properties;
        this.workspaces = workspaces;
        this.platform = platform;
        this.identities = identities;
        this.authorization = authorization;
        this.locks = locks;
        this.transactions = transactions;
        this.approvals = approvals;
        this.codec = codec;
        this.clock = clock;
        var unsigned = new ToolDefinition(TOOL, provider.definition().id(), "更新当前工作空间名称与说明",
                "仅修改本次已选择工作空间的名称与说明，不改变成员、权限、归属或 AI 配置。须当前空间管理资格、原版本及人工确认。说明为空将清空。",
                Set.of("工作空间", "业务写入"), ref("capability", "arte-workspace-details"), input.definition(), output.definition(), configuration.definition(), EMPTY,
                new ToolCapabilities(Set.of(), false), new ToolRiskProfile(CapabilityDescriptor.SideEffect.RESOURCE_WRITE, false,
                ToolRiskProfile.Idempotency.DOMAIN_OPERATION_KEY, Set.of(AdmissionAuthorization.INVOKE), Set.of()),
                new ToolExecutionPolicy(Duration.ofMinutes(16), 1, Duration.ZERO, 16384, 4096, ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION), "0".repeat(64));
        var signed = new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(), unsigned.capability(),
                unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(), unsigned.defaultConfiguration(), unsigned.capabilities(), unsigned.risk(),
                unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned));
        revision = new ToolDefinitionRevision(signed, provider.definition(), new ExecutionTarget.Local(ADAPTER));
    }

    public ToolDefinitionRevision revision() {
        return revision;
    }

    public StaticToolDefinitionSource.Definitions definitions() {
        return new StaticToolDefinitionSource.Definitions(List.of(input, output, configuration), List.of(revision));
    }

    public StaticToolRegistry.Registration registration() {
        return new StaticToolRegistry.Registration(provider, () -> Mono.just(definitions()), Map.of(ADAPTER, adapter()));
    }

    @Override
    public void requireFree(ToolAdmissionInput input, ToolBindingResolution binding) {
        if (!properties.enabled() || !revision.equals(binding.tool()) || !input.bindingId().equals(binding.definition().id())
                || !binding.effectiveConfiguration().equals(EMPTY) || !binding.secrets().isEmpty() || !binding.networkOrigins().isEmpty()
                || !binding.effectivePolicy().approvalMode().satisfies(ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION))
            throw new AccessDeniedException("Unreviewed workspace write contract");
        arguments(input.arguments());
    }

    @Override
    public void requireResource(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        requireFree(input, binding);
        transactions.required(() -> {
            requireActor(context);
            return null;
        });
    }

    private TrustedActor requireActor(ExecutionContext context) {
        transactions.requireWritable();
        var scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
        locks.scopes(context.authorization().principal().subjectId(), List.of(scope));
        var principal = identities.findCurrentExecutionPrincipal(context.authorization().principal())
                .orElseThrow(() -> new AccessDeniedException("Current workspace writer unavailable"));
        var actor = new TrustedActor(principal, clock.instant());
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_UPDATE, "tool-workspace-details"));
        return actor;
    }

    private BusinessToolAdapter adapter() {
        return new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                return revision.tool().discoveredCapability();
            }

            public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
                return Mono.fromCallable(() -> {
                    if (!properties.enabled() || !TOOL.equals(call.request().input().tool()) || !new ExecutionTarget.Local(ADAPTER).equals(call.binding().target()))
                        throw new AccessDeniedException("Workspace write disabled or target mismatch");
                    var args = arguments(call.request().input().arguments());
                    try {
                        return transactions.required(() -> {
                            var store = approvals.getIfAvailable();
                            if (store == null)
                                throw new AccessDeniedException("Transactional approval fence unavailable");
                            // 先锁原调用与 Attempt，再进入既有公共治理锁；锁持续到领域写入和回执一起提交。
                            store.requireApprovedInCurrentTransaction(call);
                            platform.requireUseInCurrentTransaction(call.runtime().execution(), Set.of(AdmissionAuthorization.INVOKE));
                            var actor = requireActor(call.runtime().execution());
                            store.requireCurrentBindingInCurrentTransaction(call);
                            var scope = new Scope(call.runtime().execution().authorization().tenantId(), call.runtime().execution().authorization().workspaceId());
                            // 原 Invocation 派生，绝不使用 Attempt、审批者或新随机键；同一领域命令能安全重放。
                            var command = new WorkspaceCommands.RenameWorkspace(new CommandMeta(operationKey(call), call.request().context().traceId()),
                                    scope, args.version(), args.name(), args.description());
                            var receipt = workspaces.rename(actor, command);
                            var result = new ToolResult(call.request().input().callId(), ToolResult.Outcome.SUCCEEDED,
                                    object(Map.of("workspaceId", text(scope.workspaceId()), "name", text(receipt.value().name()), "version", text(Long.toString(receipt.resultVersion())),
                                            "operationId", text(receipt.operationId()))), List.of(), List.of(), ExecutionError.SideEffect.CONFIRMED, null);
                            // 结果字节也在提交前核验，不能先改业务再发现本次请求连成功信封都放不下。
                            if (CanonicalJson.normalize(codec.encode(new InvocationResult.Tool(result))).getBytes(StandardCharsets.UTF_8).length
                                    > call.request().options().maxOutputBytes()) throw new OutputLimit();
                            // 版本冲突／取消／失租／到期导致本事务整体回滚，不留下孤立领域写入或回执。
                            store.requireApprovedInCurrentTransaction(call);
                            return result;
                        });
                    } catch (OutputLimit error) {
                        return failed(call, "TOOL_OUTPUT_LIMIT");
                    } catch (AccessDeniedException error) {
                        return failed(call, "TOOL_RESOURCE_ACCESS_DENIED");
                    } catch (PlatformException error) {
                        // 只有明确拒绝且事务回滚的领域错误才能报告未生效；基础设施及未知提交故障继续交给网关保守处理。
                        return switch (error.getCode()) {
                            case VERSION_CONFLICT -> failed(call, "TOOL_RESOURCE_VERSION_CONFLICT");
                            case IDEMPOTENCY_CONFLICT -> failed(call, "TOOL_DOMAIN_IDEMPOTENCY_CONFLICT");
                            case UNAUTHENTICATED, ACCESS_DENIED, POLICY_DENIED, NOT_FOUND, SPACE_READ_ONLY,
                                 SPACE_CLOSING, CAPABILITY_NOT_ENABLED, TENANT_INACTIVE, IDENTITY_CONFLICT ->
                                    failed(call, "TOOL_RESOURCE_ACCESS_DENIED");
                            default -> throw error;
                        };
                    }
                });
            }
        };
    }

    public static String operationKey(GatewayCall<ToolInvocation> call) {
        return CanonicalJson.key("tool-workspace-details-v1", call.request().context().executionId());
    }

    private static final class OutputLimit extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1812911230416606424L;
    }

    private static ToolResult failed(GatewayCall<ToolInvocation> call, String code) {
        var error = new ExecutionError(code, ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, call.request().context().traceId());
        return new ToolResult(call.request().input().callId(), ToolResult.Outcome.FAILED, null, List.of(), List.of(), ExecutionError.SideEffect.NONE, error);
    }

    private record Arguments(long version, String name, String description) {
    }

    private static Arguments arguments(StructuredValue.ObjectValue args) {
        if (!args.fields().keySet().equals(Set.of("expectedVersion", "name", "description")))
            throw new IllegalArgumentException("Expected fixed workspace details");
        String version = string(args, "expectedVersion"), name = string(args, "name"), description = string(args, "description");
        if (!version.matches("^(0|[1-9][0-9]{0,18})$") || !PlatformChecks.name(name).equals(name))
            throw new IllegalArgumentException("Expected canonical workspace values");
        PlatformChecks.description(description);
        return new Arguments(Long.parseLong(version), name, description);
    }

    private static String string(StructuredValue.ObjectValue args, String name) {
        if (!(args.fields().get(name) instanceof StructuredValue.StringValue(String value1)))
            throw new IllegalArgumentException("Expected text field");
        return value1;
    }

    private static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    private static StructuredValue.StringValue text(String s) {
        return new StructuredValue.StringValue(s);
    }

    private static StructuredValue.NumberValue number(long n) {
        return new StructuredValue.NumberValue(BigDecimal.valueOf(n));
    }

    private static StructuredValue.ObjectValue object(Map<String, StructuredValue> m) {
        return new StructuredValue.ObjectValue(m);
    }

    private static StructuredValue.ObjectValue field(String type, String title, Map<String, StructuredValue> extra) {
        var fields = new HashMap<>(extra);
        fields.put("type", text(type));
        fields.put("title", text(title));
        return object(fields);
    }

    private static ToolSchemaDefinition schema(String id, Map<String, StructuredValue> fields) {
        var document = new HashMap<String, StructuredValue>(Map.of("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"), "properties", object(fields), "additionalProperties", new StructuredValue.BooleanValue(false)));
        if (!fields.isEmpty())
            document.put("required", new StructuredValue.ArrayValue(fields.keySet().stream().sorted().map(k -> (StructuredValue) text(k)).toList()));
        var schema = object(document);
        return new ToolSchemaDefinition(ref("schema", id), schema, ToolCatalogDigests.schema(schema));
    }
}
