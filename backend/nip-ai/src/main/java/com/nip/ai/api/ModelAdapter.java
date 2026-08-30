package com.nip.ai.api;

import com.nip.ai.common.enums.ModelProviderEnum;
import com.nip.ai.pojo.chat.ChatRequestDto;
import com.nip.ai.pojo.model.ModelConfigDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

/**
 * AI 模型适配器顶层接口
 * 每个厂商/平台实现此接口，屏蔽差异性
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:58 ✾
 **/
public interface ModelAdapter {

    default String modelKey() {
        return provider().getValue() + ":" + modelId();
    }

    ModelProviderEnum provider();

    String modelId();

    ChatModel getChatModel(ModelConfigDto modelConfigDto, ChatOptions chatOptions);

    ChatClient buildChatClient(ChatRequestDto chatRequest);

    ChatOptions buildOptions(ChatRequestDto chatRequest);

    /**
     * 检查模型是否支持指定能力
     *
     * @param capability 能力键：stream/vision/thinking/search/function_call
     */
    boolean supportsCapability(String capability);

    /**
     * 从官方获取模型配置信息并初始化
     */
    ModelConfigDto getOfficialModelConfig();

    List<Message> handleResponse(ChatResponse chatResponse, Map<String, Object> context);

}
