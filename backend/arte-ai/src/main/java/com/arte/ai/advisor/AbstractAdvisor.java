package com.arte.ai.advisor;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.core.exception.ChatException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 抽象 Advisor
 * 定义一些常量或方法，规范参数设置与获取
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/18 11:26 ✾
 **/
public abstract class AbstractAdvisor implements BaseAdvisor {
    /**
     * 请求流转中使用的请求参数，根据 raw_param 填充成完整的请求过程中需要的参数
     */
    public final static String REQUEST_DTO = "request_dto";
    /**
     * 会话 ID
     */
    public final static String CONVERSATION_ID = ChatMemory.CONVERSATION_ID;
    /**
     * 原始查询
     */
    public final static String ORIGINAL_QUERY = "original_query";
    /**
     * 最终查询
     */
    public final static String FINAL_QUERY = "final_query";
    /**
     * 消息摘要
     */
    public final static String MESSAGE_DIGEST = "message_digest";
    /**
     * 耗时
     */
    public final static String LATENCY = "latency";
    /**
     * 思考内容
     */
    public final static String REASONING_CONTENT = "reasoningContent";
    /**
     * 模型参数
     */
    public final static String MODEL_PARAM = "model_param";
    /**
     * 用户消息 ID
     */
    public static final String USER_MESSAGE_ID_KEY = "userMessageId";
    /**
     * 聊天开始时间
     */
    public static final String STREAMING_START_TIME_KEY = "streamingStartTime";

    protected ChatRequestDto getChatRequestDto(Object reqOrRes) {
        Map<String, Object> context;
        if (reqOrRes instanceof ChatClientRequest chatClientRequest) {
            context = chatClientRequest.context();
        } else if (reqOrRes instanceof ChatClientResponse chatClientResponse) {
            context = chatClientResponse.context();
        } else {
            throw new ChatException("获取上下文内容时，请求参数类型错误");
        }
        ChatRequestDto requestDto = (ChatRequestDto) context.get(REQUEST_DTO);
        if (Objects.isNull(requestDto)) {
            throw new ChatException("请求参数不能为空");
        }
        return requestDto;
    }

    protected String getConversationId(Object reqOrRes) {
        Map<String, Object> context;
        if (reqOrRes instanceof ChatClientRequest chatClientRequest) {
            context = chatClientRequest.context();
        } else if (reqOrRes instanceof ChatClientResponse chatClientResponse) {
            context = chatClientResponse.context();
        } else {
            throw new ChatException("获取上下文内容时，请求参数类型错误");
        }
        String conversationId = (String) context.get(CONVERSATION_ID);
        if (StrUtil.isBlank(conversationId)) {
            throw new ChatException("会话 ID 不能为空");
        }
        return conversationId;
    }

    protected String getFinalQuery(ChatClientRequest chatClientRequest) {
        return (String) chatClientRequest.context().get(FINAL_QUERY);
    }

    @SuppressWarnings("unchecked")
    protected <T> T getFromRequest(ChatClientRequest chatClientRequest, String key) {
        Map<String, Object> context = chatClientRequest.context();
        return (T) context.get(key);
    }

    @SuppressWarnings("unchecked")
    protected <T> T getFromResponse(ChatClientResponse chatClientResponse, String key) {
        Map<String, Object> context = chatClientResponse.context();
        return (T) context.get(key);
    }

    protected Map<String, Object> putIntoRequest(boolean newOne, ChatClientRequest chatClientRequest, Object... args) {
        Map<String, Object> context = newOne ? new HashMap<>(chatClientRequest.context()) : chatClientRequest.context();
        return put(context, args);
    }

    protected Map<String, Object> putIntoResponse(boolean newOne, ChatClientResponse chatClientResponse, Object... args) {
        Map<String, Object> context = newOne ? new HashMap<>(chatClientResponse.context()) : chatClientResponse.context();
        return put(context, args);
    }

    private Map<String, Object> put(Map<String, Object> context, Object... args) {
        if (args == null || args.length == 0) {
            return context;
        }
        if (args.length % 2 != 0) {
            throw new ChatException("填充参数个数必须为偶数");
        }
        for (int i = 0; i < args.length; i += 2) {
            if (ObjectUtil.hasNull(args[i], args[i + 1])) {
                throw new ChatException(String.format("填充参数不能为空，键：%s，值：%s", args[i], args[i + 1]));
            }
            context.put((String) args[i], args[i + 1]);
        }
        return context;
    }
}
