package com.arte.ai.pojo.conversation;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话摘要 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:44 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ConversationSummaryDto extends ConversationSummaryPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 2611936941790995750L;
}
