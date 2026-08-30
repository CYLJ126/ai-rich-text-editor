package com.arte.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.ai.pojo.conversation.ConversationSummaryDto;
import com.arte.core.annotations.MybatisParams;

@MybatisParams("arte_ai_conversation_summary")
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummaryDto> {
}