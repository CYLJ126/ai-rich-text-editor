package com.arte.ai.pojo.message;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息附件 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:43 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class MessageAttachmentDto extends MessageAttachmentPo implements Serializable {
    @Serial
    private static final long serialVersionUID = -8955121694712040539L;
}
