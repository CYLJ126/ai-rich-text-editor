package com.arte.ainew.web.controller;

import com.arte.ainew.api.conversation.ConversationSearchService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationSearchRequests;
import com.arte.ainew.web.response.ConversationTurnResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 会话搜索入口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@RestController
@RequestMapping("/ai-new/conversation")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name = {"arte.ai-new.enabled", "arte.ai-new-search.enabled", "arte.ai-new-search.query-enabled"}, havingValue = "true")
public class NewAiConversationSearchController {

    private final ConversationSearchService service;
    private final NewAiHttpContext http;
    private final Duration timeout;

    public NewAiConversationSearchController(ConversationSearchService service, NewAiHttpContext http, NewAiProperties p) {
        this.service = service;
        this.http = http;
        timeout = p.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0 ? p.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    /**
     * 在当前空间内搜索本人可读且未归档的会话标题和消息，支持按游标翻页。
     */
    @PostMapping("/searchConversations")
    public Mono<ResultContext<ConversationSearch.Page>> search(@Valid @RequestBody ConversationSearchRequests.Search request, Locale locale) {
        return http.read(request.scope(), Set.of(AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ), timeout, false, context -> service.search(request.toQuery(), context))
                .map(page -> ResultContext.success(page, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 关闭指定搜索会话，释放活动名额并触发关联搜索快照的清理。
     */
    @PostMapping("/closeSearch")
    public Mono<ResultContext<Void>> close(@Valid @RequestBody ConversationSearchRequests.Close request, Locale locale) {
        return http.read(request.scope(), Set.of(AdmissionAuthorization.CONVERSATION), timeout, false, context -> service.close(request.searchSessionId(), context))
                .thenReturn(ResultContext.success(null, ResultCodeEnum.SUCCESS, locale));
    }

    public record AroundResponse(long conversationVersion, List<ConversationTurnResponse> records, String earlierCursor,
                                 String laterCursor) {
    }

    /**
     * 读取目标轮次附近的聊天历史，或按历史游标前后翻页，用于定位搜索命中的消息。
     */
    @PostMapping("/queryTurnsAround")
    public Mono<ResultContext<AroundResponse>> around(@Valid @RequestBody ConversationSearchRequests.Around request, Locale locale) {
        return http.read(request.scope(), Set.of(AdmissionAuthorization.CONVERSATION), timeout, false, context -> service.queryTurnsAround(request.toQuery(), context))
                .map(page -> ResultContext.success(new AroundResponse(page.conversationVersion(), page.records().stream().map(ConversationTurnResponse::from).toList(), page.earlierCursor(), page.laterCursor()), ResultCodeEnum.SUCCESS, locale));
    }
}
