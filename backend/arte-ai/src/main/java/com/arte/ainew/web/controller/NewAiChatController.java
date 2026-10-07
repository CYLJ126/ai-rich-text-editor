package com.arte.ainew.web.controller;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ChatRequests;
import com.arte.ainew.web.response.ChatAcceptedResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 提交用户消息，后续增加重新生成、编辑重发
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 21:38 ✾
 **/
@RestController
@RequestMapping("/ai-new/chat")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public class NewAiChatController {

    private final ChatService chatService;
    private final BindingManager bindingManager;
    private final NewAiHttpContext httpContext;
    private final NewAiProperties properties;

    public NewAiChatController(ChatService chatService, BindingManager bindingManager,
                               NewAiHttpContext httpContext, NewAiProperties properties) {
        this.chatService = chatService;
        this.bindingManager = bindingManager;
        this.httpContext = httpContext;
        this.properties = properties;
    }

    @PostMapping("/turnsForChat")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ResultContext<ChatAcceptedResponse>> turnsForChat(
            @Valid @RequestBody ChatRequests.Submit request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Locale locale) {
        var timeout = Duration.ofSeconds(request.timeoutSeconds());
        // 创建执行上下文 → 解析绑定 → 提交请求
        return httpContext.create(request.scope(), Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ),
                        timeout, request.budgetRef(), idempotencyKey)
                .flatMap(executionContext -> bindingManager.resolve(request.binding(), request.capability(), executionContext)
                        .flatMap(binding -> chatService.submit(chatRequest(request, binding, executionContext, timeout), executionContext)))
                .map(accepted -> ResultContext.success(ChatAcceptedResponse.from(request.conversationId(), accepted),
                        ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 组装聊天请求，各参数的职责及所在对象如下：
     * <ul>
     *     <li>{@code request.binding()}：选择模型绑定及版本，放在 {@link EntryRequests.Chat} 中。</li>
     *     <li>{@code request.capability()}：选择能力及版本，放在 {@link EntryRequests.Chat} 中。</li>
     *     <li>{@code request.budgetRef()}：选择费用预算账户，放在 {@link ExecutionContext} 中。</li>
     *     <li>{@link ContextBudget}：约束输入、输出和工具占用的 Token 容量，放在 {@link ContextRequest} 中。</li>
     *     <li>{@code GenerationOptions}：控制输出 Token 上限、温度等生成参数，放在 {@link EntryRequests.Chat} 中。</li>
     *     <li>{@link ExecutionOptions}：约束超时、尝试次数、输出字节等执行参数，放在 {@link EntryRequests.Chat} 中。</li>
     * </ul>
     */
    private EntryRequests.Chat chatRequest(ChatRequests.Submit request, ResolvedBinding binding,
                                           ExecutionContext context, Duration timeout) {
        // 用户消息
        var userMessage = new ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER,
                List.of(new ChatMessage.Text(request.text())), List.of(), null);
        // 上下文请求
        var selection = new ContextRequest(List.of(userMessage), null, List.of(), List.of(), null,
                new ContextBudget(binding.contextWindowTokens(), request.maxInputTokens(),
                        request.generationOptions().maxOutputTokens(), 0));
        // 聊天请求
        return new EntryRequests.Chat(request.conversationId(), request.expectedVersion(), null, null, selection,
                request.capability(), request.binding(), request.generationOptions(),
                new ExecutionOptions(context.deadline(), 1, properties.limits().maxOutputBytes(), 0, 0, timeout));
    }
}
