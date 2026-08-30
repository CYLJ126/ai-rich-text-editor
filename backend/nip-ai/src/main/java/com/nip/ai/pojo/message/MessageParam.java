package com.nip.ai.pojo.message;

import com.nip.ai.pojo.BaseParam;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;

/**
 * 消息请求参数 Param
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/21 21:41 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class MessageParam extends BaseParam<MessageDto> implements Serializable {
    @Serial
    private static final long serialVersionUID = -534526005528957934L;

    private String messageId;
    private String convId;
    private Boolean includeDeleted;
    private Collection<String> messageIds;

    /**
     * 点赞状态: 1点赞/-1点踩/0无
     */
    private Integer likeStatus;
}
