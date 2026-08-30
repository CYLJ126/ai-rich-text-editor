package com.nip.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.ai.pojo.conversation.ConversationSummaryDto;
import com.nip.core.annotations.MybatisParams;

@MybatisParams("nip_ai_conversation_summary")
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummaryDto> {
}