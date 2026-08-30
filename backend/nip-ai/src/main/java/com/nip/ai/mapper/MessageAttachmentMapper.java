package com.nip.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.ai.pojo.message.MessageAttachmentDto;
import com.nip.core.annotations.MybatisParams;

@MybatisParams("nip_ai_message_attachment")
public interface MessageAttachmentMapper extends BaseMapper<MessageAttachmentDto> {

}