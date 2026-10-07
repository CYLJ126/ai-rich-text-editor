package com.arte.ainew.web.controller;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.web.ConversationHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.ainew.web.response.ConversationResponse;
import com.arte.ainew.web.response.ConversationTurnResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 创建会话、会话列表、会话详情、历史轮次
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 21:38 ✾
 **/
@RestController
@RequestMapping("/ai-new/conversation")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public class NewAiConversationController {

    private final ConversationService conversationService;
    private final ConversationHttpContext conversationHttpContext;

    public NewAiConversationController(ConversationService conversationService, ConversationHttpContext conversationHttpContext) {
        this.conversationService = conversationService;
        this.conversationHttpContext = conversationHttpContext;
    }

    @PostMapping("/createConversation")
    public Mono<ResultContext<ConversationResponse>> createConversation(
            @Valid @RequestBody ConversationRequests.Create request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Locale locale) {
        return conversationHttpContext.create(request.scope(), idempotencyKey)
                .flatMap(context -> conversationService.create(request.title(), request.chatProfile(),
                        request.resources() == null ? List.of() : request.resources(), context))
                .map(conversation -> ResultContext.success(ConversationResponse.from(conversation), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/listConversations")
    public Mono<PageView<ConversationResponse>> listConversations(
            @Valid @RequestBody ConversationRequests.ListQuery request,
            Locale locale) {
        return conversationHttpContext.create(request.getScope(), null)
                .flatMap(context -> conversationService.list(request.getPage(), context))
                .map(page -> pageView(page, ConversationResponse::from, locale));
    }

    @PostMapping("/getConversation")
    public Mono<ResultContext<ConversationResponse>> getConversation(
            @Valid @RequestBody ConversationRequests.Find request,
            Locale locale) {
        return conversationHttpContext.create(request.scope(), null)
                .flatMap(context -> conversationService.find(request.conversationId(), context))
                .map(conversation -> ResultContext.success(ConversationResponse.from(conversation), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/queryTurnsOfConversation")
    public Mono<PageView<ConversationTurnResponse>> queryTurnsOfConversation(
            @Valid @RequestBody ConversationRequests.TurnsQuery request,
            Locale locale) {
        return conversationHttpContext.create(request.getScope(), null)
                .flatMap(context -> conversationService.turns(request.getConversationId(), request.getExpectedVersion(), request.getPage(), context))
                .map(page -> pageView(page, ConversationTurnResponse::from, locale));
    }

    private static <T, R> PageView<R> pageView(ConversationPage<T> page, Function<T, R> mapping, Locale locale) {
        var response = new PageView<R>(locale);
        response.setCurrent(page.current());
        response.setSize(page.size());
        response.setTotal(page.total());
        response.setRecords(page.records().stream().map(mapping).toList());
        return response;
    }
}
