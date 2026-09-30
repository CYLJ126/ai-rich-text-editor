package com.arte.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.ai.pojo.message.MessageAttachmentDto;
import org.springframework.ai.content.Media;
import java.util.List;
import java.util.Collection;

/**
 * 多模态消息附件服务接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 18:18 ✾
 **/
public interface MessageAttachmentService extends IService<MessageAttachmentDto> {
    List<Media> readImages(List<MessageAttachmentDto> attachments);
    List<MessageAttachmentDto> listImages(String convId, Collection<String> messageIds, String userName);
}
