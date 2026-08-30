package com.arte.ai.web.controller;

import cn.hutool.core.lang.Assert;
import com.arte.ai.api.BackEndChatService;
import com.arte.ai.api.EmbeddingService;
import com.arte.ai.api.FrontEndChatService;
import com.arte.ai.common.enums.GenerateTypeEnum;
import com.arte.ai.pojo.EditMessageRequestDto;
import com.arte.ai.pojo.FrontendSaveRequestDto;
import com.arte.ai.pojo.RegenerateRequestDto;
import com.arte.ai.pojo.chat.ChatRequestParam;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 聊天消息管理 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:35 ✾
 **/
@Slf4j
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class ChatController extends AbstractStreamController {

    @Resource
    private BackEndChatService backEndChatService;

    @Resource
    private FrontEndChatService frontEndChatService;

    @Resource
    private EmbeddingService embeddingService;

    /**
     * 流式聊天，后端向 AI 发起交互，并同步输出到前端
     *
     * @param request  聊天请求
     * @param response HTTP 响应
     * @return SSE 格式的流式分块消息
     */
    @AnonymousAccess
    @PostMapping(value = "/streamChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequestParam request, HttpServletResponse response) {
        log.debug("收到流式聊天请求, convId={}, model={}", request.getConvId(), request.getModelId());
        request.setGenerateType(GenerateTypeEnum.CHAT);
        return executeSseStream(backEndChatService.streamChat(request), response);
    }

    /**
     * 流式聊天，后端向 AI 发起交互，并同步输出到前端
     *
     * @param request  聊天请求
     * @param response HTTP 响应
     * @return SSE 格式的流式分块消息
     */
    @AnonymousAccess
    @PostMapping(value = "/streamGenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGenerate(@RequestBody ChatRequestParam request, HttpServletResponse response) {
        Assert.notNull(request.getGenerateType(), "生成类型不能为空");
        log.debug("收到流式生成请求, 类型={}", request.getGenerateType());
        return executeSseStream(backEndChatService.streamGenerate(request), response);
    }

    /**
     * 重新生成 AI 回复
     */
    @AnonymousAccess
    @PostMapping(value = "/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@RequestBody RegenerateRequestDto request, HttpServletResponse response) {
        log.debug("重新生成请求, messageId={}", request.getMessageId());
        String userName = UserContext.getUserName();
        if (!userName.isBlank()) {
            request.setUserName(userName);
        }
        return executeSseStream(backEndChatService.regenerate(request), response);
    }

    /**
     * 编辑消息并重新发送
     */
    @AnonymousAccess
    @PostMapping(value = "/edit-resend", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<@NonNull String> editAndResend(@RequestBody EditMessageRequestDto request) {
        log.debug("编辑重发请求, messageId={}", request.getMessageId());
//        return backEndChatService.editAndResend(request).doOnError(e -> log.error("编辑重发异常", e));
        return Flux.just("data: edit-resend\n\n");
    }

    /**
     * 前端直连模式：保存交互数据
     */
    @AnonymousAccess
    @PostMapping("/frontend/save")
    public ResultContext<Void> saveFrontendInteraction(@RequestBody FrontendSaveRequestDto saveRequest) {
        saveRequest.setUserName(UserContext.getUserName());
        frontEndChatService.saveMessage(saveRequest);
        return ResultContext.success();
    }

    /**
     * 为文本生成嵌入向量
     */
    @AnonymousAccess
    @PostMapping("/manualEmbedding")
    public ResultContext<Map<String, Object>> manualEmbedding(@RequestBody String content) {
        float[] embedding = embeddingService.generateEmbedding(content);
        return ResultContext.success(Map.of("embedding", embedding, "content", content, "dimensions", embedding.length));
    }
}
