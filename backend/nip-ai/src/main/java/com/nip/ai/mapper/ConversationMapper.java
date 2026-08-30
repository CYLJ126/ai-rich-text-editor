package com.nip.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.core.annotations.MybatisParams;

/**
 * 凡是在流式交互中调用的，都要注解 @MybatisParams(ignore = true)
 */
@MybatisParams(value = "nip_ai_conversation")
public interface ConversationMapper extends BaseMapper<ConversationDto> {

    @MybatisParams(ignore = true)
    ConversationDto getAndValidate(String convId);
}