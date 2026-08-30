package com.arte.ai.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.MessageAttachmentService;
import com.arte.ai.mapper.MessageAttachmentMapper;
import com.arte.ai.pojo.message.MessageAttachmentDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 多模态消息附件服务实现类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 18:19 ✾
 **/
@Slf4j
@Service
public class MessageAttachmentServiceImpl extends ServiceImpl<MessageAttachmentMapper, MessageAttachmentDto> implements MessageAttachmentService {
}
