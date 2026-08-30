package com.nip.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.ai.pojo.conversation.ConversationParam;
import com.nip.ai.pojo.conversation.ConversationUpsertDto;
import com.nip.core.pojo.PageView;

import java.time.LocalDateTime;

/**
 * 会话服务接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 16:05 ✾
 **/
public interface ConversationService extends IService<ConversationDto> {

    /**
     * 创建新会话
     */
    ConversationDto createConversation(ConversationDto dto);

    /**
     * 更新会话元数据，只能用在会话侧边栏，因为要全量更新，允许更新为空值
     */
    void fullUpdateConversation(ConversationUpsertDto dto);

    /**
     * 与模型交互时，更新用户消息，或模型响应后更新
     *
     * @param convId            会话 ID
     * @param lastMessageId     最后一条消息 ID
     * @param lastMessageAt     最后一条消息的生成时间
     * @param lastMessageDigest 最后一条消息的摘要
     * @param messageCount      本次增加的消息条数
     * @param userName          用户名
     */
    void updateWhenResponse(String convId, String lastMessageId, LocalDateTime lastMessageAt, String lastMessageDigest, Integer messageCount, String userName);

    /**
     * 增加会话消息数量，不改变最后一条用户可见消息及其摘要。
     * 用于记录工具调用请求、工具执行结果等中间协议消息。
     */
    void incrementMessageCount(String convId, int delta, String userName);

    /**
     * 分页查询当前用户的会话列表
     */
    PageView<ConversationDto> listConversations(ConversationParam param);

    /**
     * 软删除会话（逻辑删除）
     */
    void softDelete(String convId);

    /**
     * 获取会话并校验权限
     */
    ConversationDto getAndValidate(String convId);

    /**
     * 自动生成会话标题（取用户第一条消息前 20 字）
     */
    void autoGenerateTitle(String convId, String firstUserContent);
}
