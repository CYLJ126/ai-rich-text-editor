package com.arte.ai.api;

import com.arte.ai.pojo.EditMessageRequestDto;
import com.arte.ai.pojo.RegenerateRequestDto;
import com.arte.ai.pojo.chat.ChatRequestParam;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

/**
 * AI 聊天门面 Service
 * 统一入口，协调会话/消息/交互策略
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/29 13:15 ✾
 **/
public interface BackEndChatService {

    /**
     * 发起聊天
     *
     * @param chatRequestParam \
     * @return \
     */
    Flux<@NonNull String> streamChat(ChatRequestParam chatRequestParam);

    /**
     * 发起内容生成
     *
     * @param chatRequestParam \
     * @return \
     */
    Flux<@NonNull String> streamGenerate(ChatRequestParam chatRequestParam);

    /**
     * 重新生成 AI 消息
     *
     * @param request \
     * @return \
     */
    Flux<@NonNull String> regenerate(RegenerateRequestDto request);

    /**
     * 编辑用户消息并重新发送
     *
     * @param request \
     * @return \
     */
    Flux<@NonNull String> editAndResend(EditMessageRequestDto request);

}
