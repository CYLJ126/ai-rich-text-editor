package com.arte.ai.pojo.conversation;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 对话记忆表
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_ai_conversation_memory")
public class ConversationMemoryDto extends ConversationMemoryPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -5232479608854497853L;
}
