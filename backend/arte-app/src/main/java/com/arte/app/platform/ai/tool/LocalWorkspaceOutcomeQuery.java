package com.arte.app.platform.ai.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.adapter.ToolOutcomeQueryAdapter;
import com.arte.ainew.spi.persistence.ToolOutcomeStore;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.CommandStatusEnum;
import com.arte.core.pojo.platform.CommandIdentity;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.StoredCommandReceipt;
import com.arte.core.pojo.workspace.WorkspaceViews;
import com.arte.core.spi.platform.CommandReceiptStore;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 仅按原主体／Scope／领域操作键读取不可变命令回执；撤权后仍可收尾，不读取当前空间正文或执行改名。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public final class LocalWorkspaceOutcomeQuery implements ToolOutcomeQueryAdapter {

    private final LocalWorkspaceTools contract;
    private final CommandReceiptStore receipts;
    private final ObjectProvider<ToolOutcomeStore> outcomes;

    public LocalWorkspaceOutcomeQuery(LocalWorkspaceTools contract, CommandReceiptStore receipts, ObjectProvider<ToolOutcomeStore> outcomes) {
        this.contract = contract;
        this.receipts = receipts;
        this.outcomes = outcomes;
    }

    public DefinitionRef tool() {
        return LocalWorkspaceTools.TOOL;
    }

    public boolean supports(ToolExecutionSnapshot snapshot) {
        return snapshot.binding().tool().equals(contract.revision()) && snapshot.binding().effectiveConfiguration().fields().isEmpty()
                && snapshot.request().context().budgetRef() == null;
    }

    public String operationKey(ToolExecutionSnapshot snapshot) {
        return CanonicalJson.key("tool-workspace-details-v1", snapshot.request().context().executionId());
    }

    public Mono<ToolOutcomeEvidence> query(ToolOutcomeCheck.Claim claim) {
        return Mono.defer(() -> {
            var store = outcomes.getIfAvailable();
            if (store == null) return Mono.error(new IllegalStateException("Current outcome authority required"));
            if (!supports(claim.snapshot()) || !operationKey(claim.snapshot()).equals(claim.work().operationKey()))
                return Mono.error(new IllegalArgumentException("Unreviewed cleanup contract"));
            return store.query(claim, () -> read(claim));
        });
    }

    @Override
    public ToolOutcomeEvidence readEvidenceInCurrentTransaction(ToolOutcomeCheck.Claim claim) {
        if (!supports(claim.snapshot()) || !operationKey(claim.snapshot()).equals(claim.work().operationKey()))
            throw new IllegalArgumentException("Unreviewed domain evidence contract");
        // 领域 SPI 要求同一物理事务；工作项与操作身份由人工 Store 在此事务锁定并授权。
        return read(claim);
    }

    private ToolOutcomeEvidence read(ToolOutcomeCheck.Claim claim) {
        var owner = claim.owner();
        var identity = new CommandIdentity(owner.subjectId(), new CommandScope(owner.tenantId(), owner.workspaceId()), "workspace:rename", claim.work().operationKey());
        java.util.Optional<StoredCommandReceipt> found;
        try {
            found = receipts.findCurrent(identity);
        } catch (IllegalStateException corrupt) {
            // 宿主回执的投影／信封冲突是证据异常，不能按临时网络故障反复查询。
            throw new IllegalArgumentException("Invalid original command receipt", corrupt);
        }
        if (found.isEmpty()) return ToolOutcomeEvidence.unknown(claim); // 查无回执不证明未执行，不能补调 rename。
        var saved = found.orElseThrow();
        var args = claim.snapshot().input().arguments().fields();
        String version = text(args, "expectedVersion"), name = text(args, "name"), description = text(args, "description");
        if (!saved.identity().equals(identity) || !saved.resultSchema().equals("workspace:view:v1")
                || !saved.requestDigest().equals(DefaultCommandExecutor.digest(List.of(version, name, description)))
                || saved.receipt().status() != CommandStatusEnum.APPLIED || !(saved.receipt().value() instanceof WorkspaceViews.WorkspaceView view)
                || !view.scope().equals(new Scope(owner.tenantId(), owner.workspaceId())) || !view.name().equals(name) || !view.description().equals(description)
                || saved.receipt().resultVersion() != Math.incrementExact(Long.parseLong(version)) || view.version() != saved.receipt().resultVersion())
            throw new IllegalArgumentException("Original domain receipt identity mismatch");
        var receipt = saved.receipt();
        var output = new StructuredValue.ObjectValue(Map.of("workspaceId", value(owner.workspaceId()), "name", value(view.name()), "version", value(Long.toString(receipt.resultVersion())), "operationId", value(receipt.operationId())));
        var result = new ToolResult(claim.snapshot().request().input().callId(), ToolResult.Outcome.SUCCEEDED, output, List.of(), List.of(), ExecutionError.SideEffect.CONFIRMED, null);
        String digest = CanonicalJson.digest(CanonicalJson.fields("identity", identity, "requestDigest", saved.requestDigest(), "operationId", receipt.operationId(), "version", receipt.resultVersion(), "completedAt", receipt.completedAt(), "output", output));
        return new ToolOutcomeEvidence(owner, claim.work().invocationId(), claim.work().attemptId(), claim.work().operationKey(), ToolOutcomeEvidence.Decision.CONFIRMED_SUCCEEDED,
                "workspace-command:" + receipt.operationId(), digest, result);
    }

    private static String text(Map<String, StructuredValue> fields, String key) {
        if (!(fields.get(key) instanceof StructuredValue.StringValue(String value)))
            throw new IllegalArgumentException("Original workspace parameters unavailable");
        return value;
    }

    private static StructuredValue.StringValue value(String value) {
        return new StructuredValue.StringValue(value);
    }
}
