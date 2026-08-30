package com.nip.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.ai.pojo.conversation.ConversationMemoryDto;
import com.nip.core.annotations.MybatisParams;

/**
 * <p>
 * 对话记忆表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
@MybatisParams("nip_ai_conversation_memory")
public interface ConversationMemoryMapper extends BaseMapper<ConversationMemoryDto> {

}
