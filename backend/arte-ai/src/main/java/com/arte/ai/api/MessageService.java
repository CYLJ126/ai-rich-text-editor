package com.arte.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.message.MessageParam;
import com.arte.core.pojo.PageView;

import java.util.Collection;
import java.util.List;

/**
 * 消息服务接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 16:22 ✾
 **/
public interface MessageService extends IService<MessageDto> {

    PageView<MessageDto> listMessages(MessageParam param);

    List<MessageDto> selectMainBranchMessages(String convId, Integer limit);

    List<MessageDto> selectMessagesBefore(String convId, Integer beforeId, Integer limit);

    /**
     * 查询提供给模型的历史上下文，不包含工具调用协议消息。
     */
    List<MessageDto> selectConversationContextMessages(String convId, Integer limit);

    /**
     * 查询指定消息之前的模型历史上下文，不包含工具调用协议消息。
     */
    List<MessageDto> selectConversationContextMessagesBefore(String convId, Integer beforeId, Integer limit);

    void batchSoftDelete(Collection<String> messageIds);

    MessageDto getByMessageId(String messageId);

    void updateWhenResponse(String messageId, String thinkContent, String finishReason, String promptToken,
                            String completionToken, String totalToken, String reasoningToken, Integer firstTokenMs,
                            String errorCode, String errorMsg);

}
