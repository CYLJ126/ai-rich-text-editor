package com.arte.ai.pojo.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.ai.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

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
public class ConversationMemoryPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 9017690858144260451L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID
     */
    private String sessionId;

    private Long userId;

    /**
     * 记忆 JSON: {intent, confirmed_facts, entities, history_summary}
     */
    private String memoryJson;

    private Integer messageCount;

    private LocalDateTime lastActive;

    public static final String COL_ID = "id";

    public static final String COL_SESSION_ID = "session_id";

    public static final String COL_USER_ID = "user_id";

    public static final String COL_MEMORY_JSON = "memory_json";

    public static final String COL_MESSAGE_COUNT = "message_count";

    public static final String COL_LAST_ACTIVE = "last_active";
}
