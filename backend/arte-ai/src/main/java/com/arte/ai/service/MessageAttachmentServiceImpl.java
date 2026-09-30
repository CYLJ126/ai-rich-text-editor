package com.arte.ai.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.MessageAttachmentService;
import com.arte.ai.api.ChatImageReader;
import com.arte.ai.mapper.MessageAttachmentMapper;
import com.arte.ai.pojo.message.MessageAttachmentDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.util.List;
import java.util.Collection;

/**
 * 多模态消息附件服务实现类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 18:19 ✾
 **/
@Slf4j
@Service
public class MessageAttachmentServiceImpl extends ServiceImpl<MessageAttachmentMapper, MessageAttachmentDto> implements MessageAttachmentService {
    @Resource
    private ChatImageReader chatImageReader;

    @Override
    public List<MessageAttachmentDto> listImages(String convId, Collection<String> messageIds, String userName) {
        if (messageIds.isEmpty()) return List.of();
        return baseMapper.listImages(convId, messageIds, userName);
    }

    @Override
    public List<Media> readImages(List<MessageAttachmentDto> attachments) {
        return attachments.stream().filter(item -> "IMAGE".equals(item.getAttachType()))
                .map(item -> {
                    try {
                        return new Media(MimeTypeUtils.parseMimeType(item.getFileType()),
                                new ByteArrayResource(chatImageReader.readImage(item.getAccessUrl())));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).toList();
    }
}
