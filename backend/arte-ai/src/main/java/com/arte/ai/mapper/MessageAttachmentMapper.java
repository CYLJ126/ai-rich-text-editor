package com.arte.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.ai.pojo.message.MessageAttachmentDto;
import com.arte.core.annotations.MybatisParams;

@MybatisParams("arte_ai_message_attachment")
public interface MessageAttachmentMapper extends BaseMapper<MessageAttachmentDto> {

}