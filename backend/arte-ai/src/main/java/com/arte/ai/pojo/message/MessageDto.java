package com.arte.ai.pojo.message;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 消息实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:42 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class MessageDto extends MessagePo implements Serializable {
    @Serial
    private static final long serialVersionUID = 6762067818844891824L;
    /**
     * 附件列表（查询返回）
     */
    @TableField(exist = false)
    private List<MessageAttachmentDto> attachments;
    /**
     * 子分支列表（查询返回，用于分支对话树）
     */
    @TableField(exist = false)
    private List<MessageDto> branches;

    public String ensureMessageId(String messageId) {
        if (StrUtil.isBlank(getMessageId())) {
            if (StrUtil.isNotBlank(messageId)) {
                setMessageId(messageId);
            } else {
                setMessageId(UUID.randomUUID().toString().replace("-", ""));
            }
        }
        return getMessageId();
    }

    public static MessageDto buildSaveMessage(String convId, SaveMessageDto dto) {
        MessageDto save = new MessageDto();
        save.ensureMessageId(dto.getMessageId());
        save.setConvId(convId);
        save.setParentMessageId(dto.getParentMessageId());
        save.setBranchId(dto.getBranchId());
        save.setContent(dto.getContent());
        save.setReasoningContent(dto.getReasoningContent());
        save.setModelId(dto.getModelId());
        save.setStatus(dto.getStatus());
        save.setFinishReason(dto.getFinishReason());
        save.setPromptToken(dto.getPromptToken());
        save.setCompletionToken(dto.getCompletionToken());
        save.setTotalToken(dto.getTotalToken());
        save.setReasoningToken(dto.getReasoningToken());
        save.setLatencyMs(dto.getLatencyMs());
        save.setFirstTokenMs(dto.getFirstTokenMs());
        save.setErrorCode(dto.getErrorCode());
        save.setErrorMessage(dto.getErrorMessage());
        save.setRequestId(dto.getRequestId());
        save.setQuotedMessageId(dto.getQuotedMessageId());
        save.setCreateBy(dto.getUserName());
        save.setUpdateBy(dto.getUserName());
        save.setCreateTime(LocalDateTime.now());
        save.setUpdateTime(LocalDateTime.now());
        return save;
    }

    /**
     * 在 Advisor 中如果需要消息的元数据，可在此添加
     * 如果值为空不能添加到 Map 中，否则在 Advisor 中 {@link UserMessage#mutate()} 时会报错
     *
     * @return 消息元数据信息
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", getId());
        map.put("message_id", getMessageId());
        map.put("conv_id", getConvId());
        map.put("role", getRole());
        map.put("content", StrUtil.nullToDefault(getContent(), ""));
        map.put("text_type", getTextType());
        map.put("status", getStatus());
        return map;
    }

    public List<Media> getAttachments() {
        // TODO
        return List.of();
    }

}
