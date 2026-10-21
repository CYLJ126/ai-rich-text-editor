package com.arte.ainew.application.tool.chat;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.auth.ToolChatAccess;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.*;

/**
 * 每次持租只推进一个耐久检查点；网络与业务执行全部委托统一 Invocation Worker。
 * 从原结果存储恢复模型／工具事实，未知结果只等待核对，不重新提交或放行下一轮。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@Slf4j
public final class ToolChatCoordinator {

    private final ToolChatRunStore runs;
    private final ExecutionStore executions;
    private final ExecutionResultStore results;
    private final InvocationCoordinator invocations;
    private final ToolAdmissionService tools;
    private final ToolChatBudgetSelector budgets;
    private final AdmissionAuthorization authorization;
    private final ToolChatAccess access;
    private final ToolAdmissionStore toolSnapshots;
    private final NewAiProperties properties;
    private final Clock clock;
    private final Duration poll;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public ToolChatCoordinator(ToolChatRunStore runs, ExecutionStore executions, ExecutionResultStore results,
                               InvocationCoordinator invocations, ToolAdmissionService tools, ToolChatBudgetSelector budgets,
                               AdmissionAuthorization authorization, ToolChatAccess access, ToolAdmissionStore toolSnapshots, NewAiProperties properties,
                               Clock clock, Duration poll) {
        this.runs = runs;
        this.executions = executions;
        this.results = results;
        this.invocations = invocations;
        this.tools = tools;
        this.budgets = budgets;
        this.authorization = authorization;
        this.access = access;
        this.toolSnapshots = toolSnapshots;
        this.properties = properties;
        this.clock = clock;
        this.poll = poll;
    }

    private static <T> Mono<T> applied(StoreOutcome<T> outcome) {
        return outcome.successful() ? Mono.just(outcome.value()) : Mono.error(AdmissionException.fromStoreRejection(outcome.code()));
    }

    public Mono<Void> process(ToolChatCommands.Lease lease) {
        return runs.find(lease.owner(), lease.runId()).flatMap(run -> runs.snapshot(lease.owner(), lease.runId()).flatMap(fixed -> {
            if (run.state().terminal()) return Mono.empty();
            // 期限／停止收尾是基础设施职责；原资格撤销不能遗留正在运行的子执行。
            return runs.cancellationRequested(run.owner(), run.runId()).flatMap(cancelled -> {
                if (cancelled || !clock.instant().isBefore(fixed.context().deadline()))
                    return stop(lease, run, fixed, cancelled ? ToolChatRun.State.CANCELLED : ToolChatRun.State.TIMED_OUT,
                            cancelled ? null : fact(fixed, "RUN_DEADLINE_EXCEEDED", false), null);
                if (fixed.budgetPlan() == null)
                    return stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, "RUN_BUDGET_PLAN_REQUIRED", false), null);
                return authorization.prepare(fixed.context(), Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION), true,
                                current -> access.authorize(current, false).switchIfEmpty(Mono.error(new AccessDeniedException("Run access denied")))
                                        .flatMap(permit -> runs.budgets(run.owner(), run.runId()).flatMap(balance -> {
                                            if (balance.stream().anyMatch(ToolChatBudget::blocked))
                                                return blocked(lease, run, fixed, permit);
                                            return drive(lease, run, fixed, current, permit);
                                        })))
                        .onErrorResume(ProtocolFailure.class, invalid -> {
                            log.warn("AI tool chat protocol rejected, runId={}, traceId={}, phase={}, round={}, childIds={}, code={}, reason={}",
                                    run.runId(), fixed.context().traceId(), run.checkpoint().phase(), run.checkpoint().roundIndex(),
                                    run.checkpoint().children(), invalid.code, invalid.reason);
                            return stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, invalid.code, false), null);
                        })
                        .onErrorResume(AccessDeniedException.class, denied -> stop(lease, run, fixed, ToolChatRun.State.FAILED,
                                fact(fixed, "RUN_AUTHORIZATION_REVOKED", false), null))
                        .onErrorResume(AdmissionException.class, error -> {
                            if (error.getResultCode() == com.arte.core.enums.ResultCodeEnum.AI_LEASE_LOST
                                    || error.getResultCode() == com.arte.core.enums.ResultCodeEnum.AI_VERSION_CONFLICT)
                                return Mono.error(error);
                            if (!clock.instant().isBefore(fixed.context().deadline()))
                                return stop(lease, run, fixed, ToolChatRun.State.TIMED_OUT, fact(fixed, "RUN_DEADLINE_EXCEEDED", false), null);
                            // 可预期准入拒绝终止本轮；SQL／连接等基础设施异常不在此伪装业务失败。
                            return stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, error.getResultCode().name(), false), null);
                        });
            });
        }));
    }

    /**
     * 预算异常只阻止新增外发；已完成的完整最终回答仍按权威引用提交，费用完整保留。
     */
    private Mono<Void> blocked(ToolChatCommands.Lease lease, ToolChatRun run, ToolChatRunSnapshot fixed, ToolChatAccess.Permit permit) {
        if (run.checkpoint().phase() == WAITING_MODEL) {
            String id = run.checkpoint().children().getFirst();
            return executions.find(run.owner(), id).flatMap(child -> {
                if (child.state() != Invocation.State.SUCCEEDED) return Mono.just(false);
                return runs.step(run.owner(), run.runId(), id).flatMap(step -> modelResult(run, fixed, step, child))
                        .flatMap(result -> {
                            if (result.outputs().stream().anyMatch(m -> !m.toolCalls().isEmpty()))
                                return Mono.just(false);
                            return advance(lease, run, fixed, ToolChatRun.State.SUCCEEDED, FINISHED, run.checkpoint().roundIndex(), List.of(), child.result(), null, permit)
                                    .thenReturn(true);
                        });
            }).defaultIfEmpty(false).flatMap(finished -> finished ? Mono.empty()
                    : stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, "RUN_BUDGET_BLOCKED", false), permit));
        }
        return stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, "RUN_BUDGET_BLOCKED", false), permit);
    }

    private Mono<Void> drive(ToolChatCommands.Lease lease, ToolChatRun run, ToolChatRunSnapshot fixed,
                             ExecutionContext current, ToolChatAccess.Permit permit) {
        if (run.checkpoint().phase() == READY_MODEL)
            return modelContext(run, fixed).flatMap(context -> advance(lease, run, fixed, ToolChatRun.State.RUNNING, WAITING_MODEL,
                    run.checkpoint().roundIndex(), List.of(new ToolChatStep.Plan(run.checkpoint().roundIndex(), new ToolChatStep.Model(context))), null, null, permit));
        return Flux.fromIterable(run.checkpoint().children()).concatMap(id -> runs.step(run.owner(), run.runId(), id)
                        .switchIfEmpty(Mono.error(new IllegalStateException("Run checkpoint step missing"))))
                .collectList().flatMap(steps -> Flux.fromIterable(steps).concatMap(step -> executions.find(run.owner(), step.childExecutionId())
                                .map(child -> {
                                    verifyChild(fixed, step, child);
                                    return Map.entry(step.childExecutionId(), child);
                                }))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue).flatMap(children -> {
                            var unknown = children.values().stream().filter(c -> c.state() == Invocation.State.UNKNOWN).findFirst();
                            if (unknown.isPresent()) {
                                if (run.checkpoint().phase() == RECONCILING) return park(lease);
                                return advance(lease, run, fixed, ToolChatRun.State.PENDING_RECONCILIATION, RECONCILING,
                                        run.checkpoint().roundIndex(), List.of(), null, fact(fixed, "CHILD_OUTCOME_UNKNOWN", true), permit);
                            }
                            if (run.checkpoint().phase() == RECONCILING)
                                return advance(lease, run, fixed, steps.getFirst().input() instanceof ToolChatStep.Model ? ToolChatRun.State.RUNNING : ToolChatRun.State.WAITING_TOOL,
                                        steps.getFirst().input() instanceof ToolChatStep.Model ? WAITING_MODEL : WAITING_TOOLS,
                                        run.checkpoint().roundIndex(), List.of(), null, null, permit);
                            var failed = children.values().stream().filter(c -> c.state().terminal() && c.state() != Invocation.State.SUCCEEDED).findFirst();
                            if (failed.isPresent()) return stop(lease, run, fixed, ToolChatRun.State.FAILED,
                                    fact(fixed, "CHILD_" + failed.get().state().name(), false), permit);
                            if (steps.getFirst().input() instanceof ToolChatStep.Model)
                                return model(lease, run, fixed, current, permit, steps.getFirst(), children.get(steps.getFirst().childExecutionId()));
                            return toolGroup(lease, run, fixed, current, permit, steps, children);
                        }));
    }

    private Mono<Void> model(ToolChatCommands.Lease lease, ToolChatRun run, ToolChatRunSnapshot fixed, ExecutionContext current,
                             ToolChatAccess.Permit permit, ToolChatStep step, Invocation child) {
        if (child == null)
            return budgets.child(run.runId(), step.childExecutionId(), fixed.context().authorization().scopes(), fixed.context().deadline(), current)
                    .flatMap(context -> {
                        var input = new GenerationRequest(((ToolChatStep.Model) step.input()).context().messages(), fixed.generation(), fixed.tools().descriptions(), new GenerationRequest.TextOutput());
                        var options = new ExecutionOptions(context.deadline(), 1, properties.limits().maxOutputBytes(),
                                Math.min(100, fixed.limits().maxToolCalls()), fixed.limits().maxConcurrentTools(), fixed.limits().timeout());
                        var request = new InvocationRequest<>(fixed.model().capability().definition(), fixed.model().definition(), input.kind(), input, options, context);
                        return invocations.submit(new InvocationSubmission<>(request, ((ToolChatStep.Model) step.input()).context(), null, null, null));
                    }).then(park(lease));
        if (child.state() != Invocation.State.SUCCEEDED) return park(lease);
        return modelResult(run, fixed, step, child).flatMap(result -> {
            var calls = result.outputs().stream().flatMap(m -> m.toolCalls().stream()).toList();
            if (calls.isEmpty()) return advance(lease, run, fixed, ToolChatRun.State.SUCCEEDED, FINISHED,
                    run.checkpoint().roundIndex(), List.of(), child.result(), null, permit);
            if (run.toolSteps() + calls.size() > fixed.limits().maxToolCalls()
                    || run.modelSteps() >= fixed.limits().maxModelRounds())
                return stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, "RUN_EXECUTION_LIMIT_EXCEEDED", false), permit);
            return runs.steps(run.owner(), run.runId()).flatMap(previous -> {
                var used = new HashSet<String>();
                previous.stream().filter(p -> p.input() instanceof ToolChatStep.Tool).forEach(p -> used.add(((ToolChatStep.Tool) p.input()).call().callId()));
                for (var call : calls)
                    if (!used.add(call.callId()))
                        return stop(lease, run, fixed, ToolChatRun.State.FAILED, fact(fixed, "DUPLICATE_TOOL_CALL_ID", false), permit);
                return advance(lease, run, fixed, ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, run.checkpoint().roundIndex(),
                        calls.stream().map(c -> new ToolChatStep.Plan(run.checkpoint().roundIndex(), new ToolChatStep.Tool(c))).toList(), null, null, permit);
            });
        });
    }

    private Mono<Void> toolGroup(ToolChatCommands.Lease lease, ToolChatRun run, ToolChatRunSnapshot fixed, ExecutionContext current,
                                 ToolChatAccess.Permit permit, List<ToolChatStep> steps, Map<String, Invocation> children) {
        boolean allDone = children.size() == steps.size() && children.values().stream().allMatch(c -> c.state() == Invocation.State.SUCCEEDED);
        if (allDone && run.checkpoint().phase() == WAITING_APPROVAL)
            return advance(lease, run, fixed, ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, run.checkpoint().roundIndex(), List.of(), null, null, permit);
        if (allDone)
            return advance(lease, run, fixed, ToolChatRun.State.RUNNING, READY_MODEL, run.checkpoint().roundIndex() + 1, List.of(), null, null, permit);
        // 任何写提议令整组串行，避免只读和写入在同一资源上交错；审批等待也占本 Run 的并发名额。
        boolean writes = steps.stream().map(s -> ((ToolChatStep.Tool) s.input()).call()).anyMatch(c -> fixed.tools().tool(c.tool())
                .binding().tool().tool().risk().sideEffect() != com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY);
        long active = children.values().stream().filter(c -> !c.state().terminal()).count();
        int capacity = (writes ? 1 : fixed.limits().maxConcurrentTools()) - (int) active;
        var missing = steps.stream().filter(s -> !children.containsKey(s.childExecutionId())).limit(Math.max(0, capacity)).toList();
        if (!missing.isEmpty())
            return Flux.fromIterable(missing).concatMap(step -> budgets.child(run.runId(), step.childExecutionId(),
                            fixed.context().authorization().scopes(), fixed.context().deadline(), current)
                    .flatMap(context -> tools.submitChild(fixed, step, context))).then(park(lease));
        boolean waiting = children.values().stream().anyMatch(c -> c.state() == Invocation.State.WAITING_APPROVAL);
        var phase = waiting ? WAITING_APPROVAL : WAITING_TOOLS;
        if (run.checkpoint().phase() == phase) return park(lease);
        return advance(lease, run, fixed, waiting ? ToolChatRun.State.WAITING_APPROVAL : ToolChatRun.State.WAITING_TOOL,
                phase, run.checkpoint().roundIndex(), List.of(), null, null, permit);
    }

    private Mono<ContextSnapshot> modelContext(ToolChatRun run, ToolChatRunSnapshot fixed) {
        if (run.checkpoint().roundIndex() == 0) return Mono.just(fixed.initialContext());
        int previousRound = run.checkpoint().roundIndex() - 1;
        return runs.steps(run.owner(), run.runId()).flatMap(steps -> {
            var previous = steps.stream().filter(s -> s.roundIndex() == previousRound && s.input() instanceof ToolChatStep.Model).findFirst().orElseThrow();
            return executions.find(run.owner(), previous.childExecutionId()).switchIfEmpty(Mono.error(new IllegalStateException("Model child missing")))
                    .flatMap(child -> modelResult(run, fixed, previous, child)).flatMap(result -> {
                        var messages = new ArrayList<>(((ToolChatStep.Model) previous.input()).context().messages());
                        messages.addAll(result.outputs());
                        var byCall = new HashMap<String, ToolChatStep>();
                        steps.stream().filter(s -> s.roundIndex() == previousRound && s.input() instanceof ToolChatStep.Tool)
                                .forEach(s -> byCall.put(((ToolChatStep.Tool) s.input()).call().callId(), s));
                        return Flux.fromIterable(result.outputs().stream().flatMap(m -> m.toolCalls().stream()).toList())
                                .concatMap(call -> {
                                    var step = byCall.get(call.callId());
                                    if (step == null || !((ToolChatStep.Tool) step.input()).call().equals(call))
                                        return Mono.error(new IllegalStateException("Tool proposal mapping mismatch"));
                                    return executions.find(run.owner(), step.childExecutionId()).switchIfEmpty(Mono.error(new IllegalStateException("Tool child missing")))
                                            .flatMap(child -> {
                                                verifyChild(fixed, step, child);
                                                if (child.state() != Invocation.State.SUCCEEDED || child.result() == null || child.result().partial())
                                                    return Mono.error(new IllegalStateException("Tool result is not complete"));
                                                return results.find(run.owner(), step.childExecutionId(), child.result()).switchIfEmpty(Mono.error(new IllegalStateException("Tool result missing")))
                                                        .zipWith(toolSnapshots.snapshot(run.owner(), step.childExecutionId()).switchIfEmpty(Mono.error(new IllegalStateException("Tool snapshot missing"))))
                                                        .map(pair -> {
                                                            var saved = pair.getT2();
                                                            if (!saved.request().equals(child.request()) || !saved.binding().equals(fixed.tools().tool(call.tool()).binding()))
                                                                throw new IllegalStateException("Tool input authority mismatch");
                                                            if (pair.getT1() instanceof InvocationResult.Tool tool && tool.value().output() != null) {
                                                                var schema = saved.schemas().stream().filter(s -> s.definition().equals(saved.binding().tool().tool().outputSchema())).findFirst().orElseThrow();
                                                                if (!schemas.compile(schema.definition(), schema.document(), ToolSchemaValidator.Purpose.OUTPUT)
                                                                        .validate(tool.value().output(), saved.policy().maxOutputBytes()).valid())
                                                                    throw new ProtocolFailure("INVALID_TOOL_RESULT");
                                                            }
                                                            return pair.getT1();
                                                        });
                                            }).map(value -> {
                                                if (!(value instanceof InvocationResult.Tool tool) || tool.value().outcome() != ToolResult.Outcome.SUCCEEDED
                                                        || !call.callId().equals(tool.value().callId()))
                                                    throw new ProtocolFailure("INVALID_TOOL_RESULT");
                                                if (tool.value().output() == null)
                                                    throw new ProtocolFailure("TOOL_RESULT_NOT_MODEL_READABLE");
                                                // 只回传已通过执行器 Schema 的业务输出，不外发内部错误、配置、凭据、来源或产物地址。
                                                String data = CanonicalJson.encode(CanonicalJson.fields("outcome", "SUCCEEDED", "output", ToolCatalogDigests.jsonValue(tool.value().output())));
                                                return new ChatMessage(CanonicalJson.key(step.childExecutionId(), "tool-message"), ChatMessage.Role.TOOL,
                                                        List.of(new ChatMessage.Text(data)), List.of(), call.callId());
                                            });
                                }).collectList().map(outputs -> {
                                    messages.addAll(outputs);
                                    return ToolChatInputs.snapshot(CanonicalJson.key(run.runId(), "model-context", Integer.toString(run.checkpoint().roundIndex())),
                                            fixed.initialContext(), messages, fixed.tools(), run.updatedAt(), properties.limits());
                                });
                    });
        });
    }

    private Mono<ModelResult> modelResult(ToolChatRun run, ToolChatRunSnapshot fixed, ToolChatStep step, Invocation child) {
        verifyChild(fixed, step, child);
        if (child.state() != Invocation.State.SUCCEEDED || child.result() == null || child.result().partial())
            return Mono.error(new IllegalStateException("Model result is not authoritative success"));
        return results.find(run.owner(), step.childExecutionId(), child.result()).switchIfEmpty(Mono.error(new IllegalStateException("Model result missing")))
                .map(value -> {
                    if (!(value instanceof InvocationResult.Generation generation))
                        throw new IllegalStateException("Wrong model result type");
                    var result = generation.value();
                    var calls = result.outputs().stream().flatMap(m -> m.toolCalls().stream()).toList();
                    // remoteOperation 是请求模型名，供应商可返回实际服务模型标识；来源由固定子绑定及派发器验证。
                    String reason = !result.complete() ? "INCOMPLETE_RESULT"
                            : result.structuredOutput() != null ? "STRUCTURED_OUTPUT_NOT_SUPPORTED"
                            : !result.sources().isEmpty() ? "UNEXPECTED_SOURCES"
                            : (calls.isEmpty() ? result.finishReason() != ModelResult.FinishReason.STOP
                            : result.finishReason() != ModelResult.FinishReason.TOOL_CALLS) ? "FINISH_REASON_MISMATCH"
                            : result.outputs().stream().flatMap(m -> m.content().stream()).anyMatch(c -> !(c instanceof ChatMessage.Text)) ? "NON_TEXT_CONTENT" : null;
                    if (reason != null) throw new ProtocolFailure("INVALID_MODEL_OUTPUT", reason);
                    if (!calls.isEmpty() && result.outputs().size() != 1)
                        throw new ProtocolFailure("INVALID_TOOL_MESSAGE_PAIRING");
                    for (var call : calls) {
                        ToolSetSnapshot.Entry entry;
                        try {
                            entry = fixed.tools().tool(call.tool());
                        } catch (IllegalArgumentException missing) {
                            throw new ProtocolFailure("UNKNOWN_MODEL_TOOL");
                        }
                        if (!schemas.compile(entry.inputSchema().definition(), entry.inputSchema().document(), ToolSchemaValidator.Purpose.INPUT)
                                .validate(call.arguments(), entry.policy().maxInputBytes()).valid())
                            throw new ProtocolFailure("INVALID_TOOL_ARGUMENTS");
                    }
                    return result;
                });
    }

    private void verifyChild(ToolChatRunSnapshot fixed, ToolChatStep step, Invocation child) {
        var context = child.request().context();
        boolean identity = context.executionId().equals(step.childExecutionId()) && step.runId().equals(fixed.context().executionId())
                && step.runId().equals(context.parentExecutionId()) && child.conversation() == null
                && fixed.context().traceId().equals(context.traceId()) && ExecutionOwner.from(context).equals(fixed.tools().owner())
                && !context.deadline().isAfter(fixed.context().deadline())
                && fixed.context().authorization().scopes().containsAll(context.authorization().scopes());
        if (step.input() instanceof ToolChatStep.Model model)
            identity &= child.request().input() instanceof GenerationRequest g && g.messages().equals(model.context().messages())
                    && g.options().equals(fixed.generation()) && g.tools().equals(fixed.tools().descriptions())
                    && child.request().capability().equals(fixed.model().capability().definition())
                    && child.request().binding().equals(fixed.model().definition()) && Objects.equals(child.contextSnapshotId(), model.context().snapshotId());
        else {
            var call = ((ToolChatStep.Tool) step.input()).call();
            identity &= child.request().input() instanceof ToolInvocation t && t.callId().equals(call.callId())
                    && t.tool().equals(call.tool()) && t.arguments().equals(call.arguments())
                    && child.request().binding().equals(fixed.tools().tool(call.tool()).binding().definition());
        }
        if (!identity) throw new IllegalStateException("Run child identity mismatch");
    }

    private Mono<Void> stop(ToolChatCommands.Lease lease, ToolChatRun run, ToolChatRunSnapshot fixed,
                            ToolChatRun.State state, ExecutionError error, ToolChatAccess.Permit permit) {
        // 根事务之外逐个写子停止事实；进程崩溃后重放同键，全部提交后才释放会话与禁用 Run 工作项。
        return Flux.fromIterable(run.checkpoint().children()).concatMap(id -> executions.find(run.owner(), id).flatMap(child ->
                runs.step(run.owner(), run.runId(), id).flatMap(step -> {
                    verifyChild(fixed, step, child);
                    if (child.state().terminal()) return Mono.empty();
                    String key = CanonicalJson.key("tool-chat-stop-child-v1", run.runId(), id);
                    var control = new ExecutionControlRequest(key, id, key, ControlReceipt.Command.CANCEL);
                    // 受信基础设施传播根停止，不使用到期的用户执行凭据取得新的调用权限。
                    return executions.requestControl(run.owner(), control, () -> verifyChild(fixed, step, child)).flatMap(ToolChatCoordinator::applied).then();
                }))).then(advance(lease, run, fixed, state, FINISHED, run.checkpoint().roundIndex(), List.of(), null, error, permit));
    }

    private Mono<Void> advance(ToolChatCommands.Lease lease, ToolChatRun run, ToolChatRunSnapshot fixed, ToolChatRun.State state, ToolChatRun.Phase phase,
                               int round, List<ToolChatStep.Plan> plans, ResultRef result, ExecutionError error, ToolChatAccess.Permit permit) {
        String command = CanonicalJson.key("tool-chat-drive-v1", run.runId(), Long.toString(run.version()), state.name(), phase.name());
        Consumer<ToolChatCommands.Guard> guard = actual -> {
            if (!actual.run().owner().equals(lease.owner()) || !actual.run().runId().equals(lease.runId()))
                throw new AccessDeniedException("Run recovery owner mismatch");
            if (permit != null) permit.requireInCurrentTransaction(actual.snapshot());
            else if (!state.terminal() || state == ToolChatRun.State.SUCCEEDED)
                throw new AccessDeniedException("New work requires current host permit");
        };
        // 原检查点时间使重复准备的命令输入稳定；存储将过去的唤醒时间箝制到数据库当前时刻。
        return runs.advance(new ToolChatCommands.Advance(lease, run.version(), command, state, phase, round, plans,
                        phase == FINISHED ? null : run.updatedAt(), result, error), guard).flatMap(ToolChatCoordinator::applied)
                .doOnNext(receipt -> {
                    var saved = receipt.run();
                    if (error == null) {
                        log.info("AI tool chat checkpoint committed, runId={}, traceId={}, version={}, state={}, phase={}, round={}",
                                saved.runId(), fixed.context().traceId(), saved.version(), saved.state(), saved.checkpoint().phase(), saved.checkpoint().roundIndex());
                    } else {
                        var now = clock.instant();
                        log.warn("AI tool chat failure committed, runId={}, traceId={}, version={}, state={}, previousPhase={}, round={}, childIds={}, code={}, certainty={}, sideEffect={}, deadline={}, observedAt={}, remainingMs={}",
                                saved.runId(), fixed.context().traceId(), saved.version(), saved.state(), run.checkpoint().phase(), run.checkpoint().roundIndex(),
                                run.checkpoint().children(), error.code(), error.certainty(), error.sideEffect(), fixed.context().deadline(), now,
                                Duration.between(now, fixed.context().deadline()).toMillis());
                    }
                }).then();
    }

    private Mono<Void> park(ToolChatCommands.Lease lease) {
        return runs.park(lease, poll).flatMap(ToolChatCoordinator::applied).then();
    }

    private static ExecutionError fact(ToolChatRunSnapshot fixed, String code, boolean unknown) {
        return new ExecutionError(code, ExecutionError.Phase.INVOCATION, false,
                unknown ? ExecutionError.SideEffect.POSSIBLE : ExecutionError.SideEffect.NONE,
                unknown ? ExecutionError.Certainty.UNKNOWN : ExecutionError.Certainty.KNOWN, fixed.context().traceId());
    }

    /**
     * 已知协议拒绝与存储损坏／基础设施异常分开处理，错误码不含模型或工具正文。
     */
    private static final class ProtocolFailure extends RuntimeException {
        private final String code;
        private final String reason;

        private ProtocolFailure(String code) {
            this(code, code);
        }

        private ProtocolFailure(String code, String reason) {
            super(code);
            this.code = code;
            this.reason = reason;
        }
    }
}
