package com.arte.ainew.tool.execution;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.chat.ToolChatQueryService;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.ToolChatRun;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 前端重新生成的服务端安全边界，使用真实同库受理及固定工具集合。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 8:20 ✾
 */
public class ToolChatFrontendContractTest {

    @Test
    public void textOnlyModelFailsWithExplicitCodeBeforeCreatingRunOrDispatchingChildren() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(false, CapabilityDescriptor.SideEffect.READ_ONLY, false)) {
            var failure = assertThrows(AdmissionException.class, () -> r.service.submit(r.input(), r.session, r.context).block(ToolChatLoopTest.WAIT));
            assertEquals(com.arte.core.enums.ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY, failure.getResultCode());
            assertEquals(0, r.count("arte_ai_tool_chat_run"));
            assertEquals(0, r.count("arte_ai_invocation"));
            assertEquals(0, r.modelCalls.get());
        }
    }

    @Test
    public void toolDeclarationAloneDoesNotEnableAnUnsupportedGateway() throws Exception {
        try (var r = new ToolChatLoopTest.Rig()) {
            r.gateway = new com.arte.ainew.spi.gateway.ModelGateway() {
                public reactor.core.publisher.Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(com.arte.ainew.pojo.execution.GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                    throw new AssertionError("Must reject before dispatch");
                }
            };
            var failure = assertThrows(AdmissionException.class, () -> r.service.submit(r.input(), r.session, r.context).block(ToolChatLoopTest.WAIT));
            assertEquals(com.arte.core.enums.ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY, failure.getResultCode());
            assertEquals(0, r.count("arte_ai_tool_chat_run"));
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void readOnlyRefreshAcceptsAndReplaysOneOriginalRun() throws Exception {
        try (var r = new ToolChatLoopTest.Rig()) {
            var first = r.service.submit(r.input(), r.session, r.context, true).block(ToolChatLoopTest.WAIT);
            var replay = r.service.submit(r.input(), r.session, r.context, true).block(ToolChatLoopTest.WAIT);
            assertEquals(first, replay);
            assertEquals(1, r.count("arte_ai_tool_chat_run"));
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void readOnlyRefreshRejectsWriteToolBeforeCreatingRunAndOnReplay() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(true, CapabilityDescriptor.SideEffect.RESOURCE_WRITE)) {
            assertThrows(AdmissionException.class, () -> r.service.submit(r.input(), r.session, r.context, true).block(ToolChatLoopTest.WAIT));
            assertEquals(0, r.count("arte_ai_tool_chat_run"));
            r.service.submit(r.input(), r.session, r.context).block(ToolChatLoopTest.WAIT);
            assertThrows(AdmissionException.class, () -> r.service.submit(r.input(), r.session, r.context, true).block(ToolChatLoopTest.WAIT));
            assertEquals(1, r.count("arte_ai_tool_chat_run"));
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void preparationIsOnlyForAuthorizedSuccessfulOriginalRunAndContainsNoRawToolData() throws Exception {
        try (var r = new ToolChatLoopTest.Rig()) {
            var query = new ToolChatQueryService(r.runs, r.conversations, r.authorization, r.f.executions, r.f.payloads);
            r.submit();
            assertThrows(AdmissionException.class, () -> query.actions("run", r.context).block(ToolChatLoopTest.WAIT));
            r.until(run -> run.state() == ToolChatRun.State.SUCCEEDED);
            var actions = query.actions("run", r.context).block(ToolChatLoopTest.WAIT);
            assertEquals("question", actions.text());
            assertEquals(java.util.List.of(r.f.binding.bindingId()), actions.readOnlyBindingIds());
            assertTrue(actions.writeBindingIds().isEmpty());
            assertEquals(r.run().updatedAt(), actions.answeredAt());
            assertThrows(AdmissionException.class, () -> query.actions("missing", r.context).block(ToolChatLoopTest.WAIT));
        }
    }
}
