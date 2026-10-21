package com.arte.ainew.application.tool.chat;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.tool.ToolChatBudget;
import com.arte.ainew.pojo.tool.ToolChatLink;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolChatRunEvent;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 聊天 Run 只读入口：每次复核当前 READ、会话权限、归档及类型化候选归属，不读取工具参数或固定授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public final class ToolChatQueryService {

    private final ToolChatRunStore runs;
    private final ConversationService conversations;
    private final AdmissionAuthorization authorization;
    private final ExecutionStore executions;
    private final ExecutionResultStore results;

    public ToolChatQueryService(ToolChatRunStore runs, ConversationService conversations, AdmissionAuthorization authorization,
                                ExecutionStore executions, ExecutionResultStore results) {
        this.runs = runs;
        this.conversations = conversations;
        this.authorization = authorization;
        this.executions = executions;
        this.results = results;
    }

    private record Current(ToolChatRun run, ToolChatLink link) {
    }

    private Mono<Current> current(String id, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(trusted -> {
            var owner = ExecutionOwner.from(trusted);
            return runs.find(owner, id).switchIfEmpty(missing()).flatMap(run -> runs.link(owner, id).switchIfEmpty(missing())
                    .flatMap(link -> conversations.find(link.conversationId(), trusted).then(conversations.turn(link.conversationId(), link.turnId(), trusted)).flatMap(turn -> {
                        if (!turn.candidates().contains(AnswerCandidate.run(id))) return missing();
                        return Mono.just(new Current(run, link));
                    })));
        });
    }

    private static <T> Mono<T> missing() {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND));
    }

    public Mono<ToolChatRun> status(String id, ExecutionContext context) {
        return current(id, context).map(Current::run);
    }

    /**
     * 重新生成的准备信息；仅返回原问题及选择引用，不返回配置、参数或旧授权。
     */
    public record Actions(String runId, String text, java.time.Instant answeredAt,
                          List<String> readOnlyBindingIds, List<String> writeBindingIds) {
    }

    public Mono<Actions> actions(String id, ExecutionContext context) {
        return current(id, context).flatMap(c -> {
            if (c.run().state() != ToolChatRun.State.SUCCEEDED)
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED));
            return runs.snapshot(ExecutionOwner.from(context), id).switchIfEmpty(missing()).map(fixed -> {
                var entries = fixed.tools().entries();
                return new Actions(id, ((com.arte.ainew.pojo.generation.ChatMessage.Text) fixed.initialContext().messages().getLast().content().getFirst()).text(),
                        c.run().updatedAt(), entries.stream().filter(e -> e.binding().tool().tool().risk().sideEffect() == com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY)
                        .map(e -> e.binding().definition().id()).toList(),
                        entries.stream().filter(e -> e.binding().tool().tool().risk().sideEffect() != com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY)
                                .map(e -> e.binding().definition().id()).toList());
            });
        }).flatMap(value -> current(id, context).thenReturn(value));
    }

    public record Status(ToolChatRun run, boolean cancellationRequested) {
    }

    /**
     * 根版本未推进前也能显示耐久停止请求，页面刷新不丢失“正在停止”。
     */
    public Mono<Status> statusView(String id, ExecutionContext context) {
        return current(id, context).flatMap(c -> runs.cancellationRequested(c.run().owner(), id)
                .map(cancelled -> new Status(c.run(), cancelled)));
    }

    public Mono<List<ToolChatRunEvent>> events(String id, long after, int limit, ExecutionContext context) {
        return current(id, context).flatMap(c -> {
            if (after > c.run().version() + 1)
                return Mono.error(new IllegalArgumentException("Run cursor exceeds committed sequence"));
            return runs.events(ExecutionOwner.from(context), id, after, limit)
                    .flatMap(page -> current(id, context).thenReturn(page));
        });
    }

    /**
     * 按币种返回 L／C／H 与异常阻断，不暴露子账户引用；旧 Run 返回空列表。
     */
    public Mono<List<ToolChatBudget>> budgets(String id, ExecutionContext context) {
        return current(id, context).flatMap(c -> runs.budgets(ExecutionOwner.from(context), id))
                .flatMap(values -> current(id, context).thenReturn(values));
    }

    public record Answer(String runId, String finalInvocationId, InvocationResult.Generation result) {
    }

    public Mono<Answer> result(String id, ExecutionContext context) {
        return current(id, context).flatMap(c -> {
            if (c.run().state() != ToolChatRun.State.SUCCEEDED || c.run().result() == null || c.link().finalInvocationId() == null)
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE));
            var owner = ExecutionOwner.from(context);
            return executions.find(owner, c.link().finalInvocationId()).switchIfEmpty(missing()).flatMap(child -> {
                if (child.state() != Invocation.State.SUCCEEDED || !id.equals(child.request().context().parentExecutionId())
                        || !c.run().result().equals(child.result()))
                    return Mono.error(new IllegalStateException("Run final answer identity mismatch"));
                return results.find(owner, c.link().finalInvocationId(), c.run().result())
                        .switchIfEmpty(Mono.error(new IllegalStateException("Committed Run answer is missing")))
                        .flatMap(value -> value instanceof InvocationResult.Generation g ? Mono.just(new Answer(id, c.link().finalInvocationId(), g))
                                : Mono.error(new IllegalStateException("Run final answer type mismatch")));
            });
        }).flatMap(answer -> current(id, context).thenReturn(answer));
    }
}
