package com.arte.ai.pojo.conversation;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Fork(分支)请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:51 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ForkConversationDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -5983104466174291088L;
    /**
     * 从哪条消息开始fork
     */
    private String fromMessageId;
    /**
     * 新的用户消息内容（fork时重新输入）
     */
    private String newContent;
    /**
     * 附件ID列表
     */
    private List<String> attachmentIds;
}
