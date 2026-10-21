package com.arte.ainew.application.tool.release;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.serialization.ToolReleaseDigests;
import com.arte.core.application.platform.PlatformTimes;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static com.arte.ainew.pojo.tool.ToolPublicationValidation.Issue.*;

/**
 * 本地发布预检
 * <p>
 * 只读受信元数据并校验审核示例，不试调用、不探测远端、不授予权限。
 * 即使发现曾成功，发布及重新启用仍重新核对当前实例声明和完整契约。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public final class LocalToolPublicationValidator {

    private final LocalToolDiscovery discovery;
    private final LocalToolRegistry registry;
    private final ToolSchemaValidator schemas;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Duration timeout;

    public LocalToolPublicationValidator(LocalToolDiscovery discovery, LocalToolRegistry registry, ToolSchemaValidator schemas,
                                         Scheduler scheduler, Clock clock, Duration timeout) {
        this.discovery = Objects.requireNonNull(discovery);
        this.registry = Objects.requireNonNull(registry);
        this.schemas = Objects.requireNonNull(schemas);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        ContractChecks.require(!timeout.isNegative() && !timeout.isZero()
                && timeout.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid publication validation timeout");
    }

    /**
     * 生成服务端预检，不接受客户端自报校验通过。
     *
     * @param tool   固定工具版本。
     * @param review 人工审核材料；缺失时返回安全失败，不能自动补成已审核。
     * @return 固定摘要或稳定问题码；所有检查在有界元数据线程执行。
     */
    public Mono<ToolPublicationValidation> validate(DefinitionRef tool, ToolReleaseCommand.Review review) {
        Objects.requireNonNull(tool).requireType("tool");
        return Mono.fromCallable(() -> inspect(tool, review)).subscribeOn(scheduler).timeout(timeout)
                .onErrorResume(error -> Mono.just(failed(IMPLEMENTATION_MISMATCH)));
    }

    private ToolPublicationValidation inspect(DefinitionRef ref, ToolReleaseCommand.Review review) {
        var state = discovery.state();
        if (!state.discoveryHealthy()) return failed(DISCOVERY_UNHEALTHY);
        var snapshot = state.snapshot();
        var revision = snapshot.tools().get(ref);
        if (revision == null) return failed(NOT_DISCOVERED);
        if (review == null) return failed(RISK_UNREVIEWED);
        var provider = snapshot.providers().get(revision.provider());
        if (provider == null) return failed(IMPLEMENTATION_MISMATCH);
        var adapter = registry.executor(revision).orElse(null);
        if (adapter == null) return failed(IMPLEMENTATION_MISMATCH);
        var actual = adapter.capability();
        var tool = revision.tool();
        if (actual == null || actual.availability() == CapabilityDescriptor.Availability.DISABLED
                || !new CapabilityDescriptor(actual.definition(), actual.kind(), actual.inputSchema(), actual.outputSchema(), actual.features(),
                actual.sideEffect(), CapabilityDescriptor.Availability.DISCOVERED).equals(tool.discoveredCapability()))
            return failed(IMPLEMENTATION_MISMATCH);

        var issues = EnumSet.noneOf(ToolPublicationValidation.Issue.class);
        // 当前业务端口只有单次最终结果；尚无流、任务、取消、用量或业务核对协议可供发布验收。
        if (!tool.capabilities().features().isEmpty() || tool.capabilities().outcomeQuerySupported())
            issues.add(UNSUPPORTED_CAPABILITY);
        if (tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.UNKNOWN) issues.add(RISK_UNREVIEWED);
        if (tool.risk().idempotency() == ToolRiskProfile.Idempotency.UNVERIFIED
                || tool.risk().idempotency() != ToolRiskProfile.Idempotency.NONE && review.idempotencyEvidence() == null)
            issues.add(IDEMPOTENCY_UNVERIFIED);
        // 首批外部副作用采取保守发布规则，人工审核不能把它降至普通本人确认。
        var minimum = tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.EXTERNAL_EFFECT
                ? ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL : tool.risk().minimumApprovalMode();
        if (!review.minimumApproval().satisfies(minimum) || !tool.defaultPolicy().approvalMode().satisfies(review.minimumApproval()))
            issues.add(POLICY_TOO_WEAK);

        var documents = new HashMap<DefinitionRef, ToolSchemaDefinition>();
        try {
            for (var schemaRef : List.of(tool.inputSchema(), tool.outputSchema(), tool.configurationSchema())) {
                var document = Objects.requireNonNull(snapshot.schemas().get(schemaRef));
                if (!document.checksum().equals(ToolCatalogDigests.schema(document.document())))
                    return failed(SCHEMA_INVALID);
                documents.put(schemaRef, document);
            }
            if (!tool.checksum().equals(ToolCatalogDigests.tool(tool))) return failed(SCHEMA_INVALID);
            var input = schemas.compile(tool.inputSchema(), documents.get(tool.inputSchema()).document(), ToolSchemaValidator.Purpose.INPUT);
            var output = schemas.compile(tool.outputSchema(), documents.get(tool.outputSchema()).document(), ToolSchemaValidator.Purpose.OUTPUT);
            var configuration = schemas.compile(tool.configurationSchema(), documents.get(tool.configurationSchema()).document(), ToolSchemaValidator.Purpose.CONFIGURATION);
            if (!configuration.validate(tool.defaultConfiguration(), 1_000_000).valid()) issues.add(SCHEMA_INVALID);
            for (var example : review.examples()) {
                if (!input.validate(example.input(), Math.min(ToolReleaseCommand.MAX_EXAMPLE_BYTES, tool.defaultPolicy().maxInputBytes())).valid()
                        || !output.validate(example.output(), Math.min(ToolReleaseCommand.MAX_EXAMPLE_BYTES, tool.defaultPolicy().maxOutputBytes())).valid())
                    issues.add(EXAMPLE_INVALID);
            }
        } catch (RuntimeException error) {
            issues.add(SCHEMA_INVALID);
        }
        if (!issues.isEmpty())
            return new ToolPublicationValidation(null, null, List.copyOf(issues), PlatformTimes.businessNow(clock));
        return new ToolPublicationValidation(ToolReleaseDigests.contract(revision, provider, documents.values()),
                snapshot.checksum(), List.of(), PlatformTimes.businessNow(clock));
    }

    private ToolPublicationValidation failed(ToolPublicationValidation.Issue issue) {
        return new ToolPublicationValidation(null, null, List.of(issue), PlatformTimes.businessNow(clock));
    }
}
