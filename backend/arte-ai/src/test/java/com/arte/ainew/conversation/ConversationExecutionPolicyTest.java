package com.arte.ainew.conversation;

import com.arte.ainew.application.conversation.ConversationExecutionPolicy;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 普通文本资格、未知费用与父子执行隔离的统一判定契约；不调用外部模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 11:30 ✾
 */
public class ConversationExecutionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final ExecutionOwner OWNER = new ExecutionOwner("tenant", "workspace", "subject");
    private static final ChatMessage USER = new ChatMessage("user", ChatMessage.Role.USER, List.of(new ChatMessage.Text("hello")), List.of(), null);
    private static final GenerationRequest TEXT = input(List.of(USER), List.of(), new GenerationRequest.TextOutput());
    private static final Conversation CONVERSATION = conversation(OWNER);
    private static final Turn TURN = turn(List.of(AnswerCandidate.invocation("invocation")));

    private static Conversation conversation(ExecutionOwner owner) {
        return new Conversation("conversation", owner, "test", 2, null, List.of(), Conversation.State.ACTIVE, NOW, NOW);
    }

    private static Turn turn(List<AnswerCandidate> candidates) {
        return Turn.typed("turn", "conversation", 1, null, null, USER, candidates, null, 0, NOW, NOW);
    }

    private static GenerationRequest input(List<ChatMessage> messages, List<GenerationRequest.ToolDefinition> tools, GenerationRequest.OutputFormat format) {
        return new GenerationRequest(messages, new GenerationOptions(128, null, null, List.of()), tools, format);
    }

    private static Invocation invocation(GenerationRequest input, Invocation.State state, String parent, boolean linked, String code) {
        var authorization = new ExecutionAuthorization(new ExecutionPrincipal("subject", "user", ExecutionPrincipal.Kind.USER),
                "tenant", "workspace", Set.of("ai:read"), "grant");
        var context = new ExecutionContext("invocation", "trace", authorization, NOW.plusSeconds(60), parent, "budget", "release", "key");
        var request = new InvocationRequest<>(new DefinitionRef("capability", "text", "v1"), new DefinitionRef("binding", "tool-capable-model", "v1"),
                input.kind(), input, new ExecutionOptions(context.deadline(), 1, 1024, input.tools().isEmpty() ? 0 : 1, input.tools().isEmpty() ? 0 : 1), context);
        var error = state == Invocation.State.UNKNOWN || state == Invocation.State.FAILED || state == Invocation.State.TIMED_OUT || state == Invocation.State.INTERRUPTED
                ? new ExecutionError(code, ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE,
                state == Invocation.State.UNKNOWN ? ExecutionError.Certainty.UNKNOWN : ExecutionError.Certainty.KNOWN, "trace") : null;
        var result = state == Invocation.State.SUCCEEDED ? new ResultRef("result", "generation-result", 1, "a".repeat(64), false) : null;
        return new Invocation(request, "a".repeat(64), linked ? new Invocation.ConversationLink("conversation", 0, "turn") : null,
                "snapshot", null, state, 1, "attempt", result, error, NOW, NOW);
    }

    @Test
    public void everyActiveStateBlocksAndEveryPlainTextTerminalDoesNotBlock() {
        for (var state : Invocation.State.values()) {
            if (state == Invocation.State.WAITING_APPROVAL) continue;
            var invocation = invocation(TEXT, state, null, true, "HTTP_500");
            assertTrue(ConversationExecutionPolicy.ordinaryText(invocation, CONVERSATION, TURN));
            assertEquals(state.name(), !state.terminal(), ConversationExecutionPolicy.blocksConversation(invocation, CONVERSATION, TURN));
        }
        // 停止和其他未知错误使用同一资格规则，不靠取消错误码取得例外。
        assertFalse(ConversationExecutionPolicy.blocksConversation(invocation(TEXT, Invocation.State.UNKNOWN, null, true, "INVOCATION_CANCELLED"), CONVERSATION, TURN));
    }

    @Test
    public void toolsStructuredOutputAndToolMessagesCannotQualify() {
        var tool = new GenerationRequest.ToolDefinition(new DefinitionRef("tool", "read", "v1"), "read",
                new DefinitionRef("schema", "arguments", "v1"), com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY);
        var call = new ToolCall("call", tool.tool(), new StructuredValue.ObjectValue(Map.of()));
        var assistant = new ChatMessage("assistant", ChatMessage.Role.ASSISTANT, List.of(), List.of(call), null);
        var response = new ChatMessage("response", ChatMessage.Role.TOOL, List.of(new ChatMessage.Text("done")), List.of(), "call");
        for (var input : List.of(input(List.of(USER), List.of(tool), new GenerationRequest.TextOutput()),
                input(List.of(USER, assistant), List.of(), new GenerationRequest.TextOutput()),
                input(List.of(USER, response), List.of(), new GenerationRequest.TextOutput()),
                input(List.of(USER), List.of(), new GenerationRequest.StructuredOutput(new DefinitionRef("schema", "output", "v1"))))) {
            var invocation = invocation(input, Invocation.State.UNKNOWN, null, true, "INVOCATION_CANCELLED");
            assertFalse(invocation.ordinaryTextRequest());
            assertTrue(ConversationExecutionPolicy.blocksConversation(invocation, CONVERSATION, TURN));
        }
    }

    @Test
    public void directToolUnknownAndApprovalRemainBlocking() {
        var base = invocation(TEXT, Invocation.State.UNKNOWN, null, true, "INVOCATION_CANCELLED");
        var input = new com.arte.ainew.pojo.tool.ToolInvocation("call", new DefinitionRef("tool", "read", "v1"), new StructuredValue.ObjectValue(Map.of()));
        var request = new InvocationRequest<>(base.request().capability(), base.request().binding(), input.kind(), input, base.request().options(), base.request().context());
        for (var state : List.of(Invocation.State.UNKNOWN, Invocation.State.WAITING_APPROVAL)) {
            var tool = new Invocation(request, base.requestDigest(), base.conversation(), null, null, state, 1,
                    state == Invocation.State.UNKNOWN ? "attempt" : null, null, state == Invocation.State.UNKNOWN ? base.error() : null, NOW, NOW);
            assertFalse(ConversationExecutionPolicy.ordinaryText(tool, CONVERSATION, TURN));
            assertTrue(ConversationExecutionPolicy.blocksConversation(tool, CONVERSATION, TURN));
        }
    }

    @Test
    public void childrenAndStandaloneCallsNeverReleaseOrDescribeParentOccupancy() {
        for (var state : Invocation.State.values()) {
            if (state == Invocation.State.WAITING_APPROVAL) continue;
            assertFalse(ConversationExecutionPolicy.blocksConversation(invocation(TEXT, state, "run", false, "HTTP_500"), null, null));
            assertFalse(ConversationExecutionPolicy.blocksConversation(invocation(TEXT, state, "run", true, "HTTP_500"), CONVERSATION, TURN));
            assertFalse(ConversationExecutionPolicy.blocksConversation(invocation(TEXT, state, null, false, "HTTP_500"), null, null));
        }
        assertFalse(ConversationExecutionPolicy.ordinaryText(invocation(TEXT, Invocation.State.UNKNOWN, "run", true, "HTTP_500"), CONVERSATION, TURN));
    }

    @Test
    public void missingWrongOwnerAndWrongCandidateFailClosed() {
        var invocation = invocation(TEXT, Invocation.State.UNKNOWN, null, true, "HTTP_500");
        assertTrue(ConversationExecutionPolicy.blocksConversation(invocation, null, TURN));
        assertTrue(ConversationExecutionPolicy.blocksConversation(invocation, CONVERSATION, null));
        assertTrue(ConversationExecutionPolicy.blocksConversation(invocation, conversation(new ExecutionOwner("tenant", "workspace", "other")), TURN));
        assertTrue(ConversationExecutionPolicy.blocksConversation(invocation, CONVERSATION, turn(List.of(AnswerCandidate.invocation("other")))));
        assertTrue(ConversationExecutionPolicy.blocksConversation(invocation, CONVERSATION, turn(List.of(AnswerCandidate.run("invocation")))));
    }

    private ConversationExecutionPolicy policy(Mono<Conversation> conversation, Mono<Turn> turn) {
        var store = (AdmissionCatalogStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AdmissionCatalogStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findConversation" -> conversation;
                    case "findTurn" -> turn;
                    default -> throw new AssertionError("Unexpected store operation " + method.getName());
                });
        return new ConversationExecutionPolicy(store);
    }

    @Test
    public void projectionReadsDurableLinksAndDoesNotSwallowStorageFailure() {
        var invocation = invocation(TEXT, Invocation.State.UNKNOWN, null, true, "HTTP_500");
        assertFalse(policy(Mono.just(CONVERSATION), Mono.just(TURN)).blocksConversation(invocation).block());
        assertTrue(policy(Mono.empty(), Mono.just(TURN)).blocksConversation(invocation).block());
        assertTrue(policy(Mono.just(CONVERSATION), Mono.empty()).blocksConversation(invocation).block());
        assertThrows(IllegalStateException.class, () -> policy(Mono.error(new IllegalStateException("storage unavailable")), Mono.just(TURN))
                .blocksConversation(invocation).block());
    }
}
