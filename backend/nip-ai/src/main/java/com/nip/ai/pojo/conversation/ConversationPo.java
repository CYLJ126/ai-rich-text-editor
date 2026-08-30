package com.nip.ai.pojo.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.nip.ai.common.enums.*;
import com.nip.ai.pojo.BaseDto;
import com.nip.core.enums.TextTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.ibatis.type.JdbcType;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 会话实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:40 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName(value = "nip_ai_conversation", autoResultMap = true)
public class ConversationPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 2651156068644108032L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    /**
     * 会话 ID
     */
    private String convId;
    /**
     * 会话标题
     */
    private String title;
    /**
     * 助手 ID
     */
    private Integer assistantId;
    /**
     * 模型 ID
     */
    private Integer modelId;
    /**
     * 额外参数
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extraParam;
    /**
     * 系统提示
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String systemPrompt;
    /**
     * 知识库 IDs
     */
    private KnowledgeBaseTypeEnum knowledgeBaseId;
    /**
     * 上下文策略
     */
    private ContextStrategyEnum contextStrategy;
    /**
     * 滑动窗口时的上下文数，或 token 数，具体取决于 contextStrategy
     */
    private Integer contextWindow;
    /**
     * 推理力度：minimal / low / medium / high
     */
    private ReasoningEffortEnum reasoningEffort;
    /**
     * 文本类型
     */
    private TextTypeEnum textType;
    /**
     * 温度参数
     */
    private BigDecimal temperature;
    /**
     * 最大生成 token 数
     */
    private Integer maxTokens;
    /**
     * Top P 参数
     */
    private BigDecimal topP;
    /**
     * Top K 参数，0 ~ 100
     */
    private Integer topK;
    /**
     * 存在惩罚参数，-2.0 ~ 2.0
     */
    private BigDecimal presencePenalty;
    /**
     * 频率惩罚参数，-2.0 ~ 2.0
     */
    private BigDecimal frequencyPenalty;
    /**
     * 是否开启全局记忆功能
     */
    private Boolean globalMemoryFlag;
    /**
     * 会话状态
     */
    private ConversationStatusEnum status;
    /**
     * 置顶状态
     */
    private Boolean pinFlag;

    /**
     * 是否开启查询重写功能
     */
    private Boolean queryRewriteFlag;

    // ---------------------- 只读字段（不允许前端编辑） ----------------------
    /**
     * 场景，第一次设置后不允许修改
     */
    private SceneTypeEnum scene;
    /**
     * 交互类型，第一次设置后不允许修改
     */
    private InterActionTypeEnum interactionType;
    /**
     * 最后一条消息 ID，每次聊天后端自动更新
     */
    private String lastMessageId;
    /**
     * 最后一条消息时间，每次聊天后端自动更新
     */
    private LocalDateTime lastMessageAt;
    /**
     * 最后一条消息的摘要
     */
    private String lastMessageDigest;
    /**
     * 消息数量
     */
    private Integer messageCount;
    /**
     * 是否默认/临时会话
     */
    private Boolean defaultFlag;

    public static final String COL_ID = "id";
    public static final String COL_CONV_ID = "conv_id";
    public static final String COL_TITLE = "title";
    public static final String COL_ASSISTANT_ID = "assistant_id";
    public static final String COL_MODEL_ID = "model_id";
    public static final String COL_EXTRA_PARAM = "extra_param";
    public static final String COL_SYSTEM_PROMPT = "system_prompt";
    public static final String COL_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    public static final String COL_CONTEXT_STRATEGY = "context_strategy";
    public static final String COL_CONTEXT_WINDOW = "context_window";
    public static final String COL_REASONING_EFFORT = "reasoning_effort";
    public static final String COL_TEXT_TYPE = "text_type";
    public static final String COL_TEMPERATURE = "temperature";
    public static final String COL_MAX_TOKENS = "max_tokens";
    public static final String COL_TOP_P = "top_p";
    public static final String COL_TOP_K = "top_k";
    public static final String COL_PRESENCE_PENALTY = "presence_penalty";
    public static final String COL_FREQUENCY_PENALTY = "frequency_penalty";
    public static final String COL_GLOBAL_MEMORY_FLAG = "global_memory_flag";
    public static final String COL_STATUS = "status";
    public static final String COL_PIN_FLAG = "pin_flag";
    public static final String COL_QUERY_REWRITE_FLAG = "query_rewrite_flag";
    public static final String COL_SCENE = "scene";
    public static final String COL_INTERACTION_TYPE = "interaction_type";
    public static final String COL_LAST_MESSAGE_ID = "last_message_id";
    public static final String COL_LAST_MESSAGE_AT = "last_message_at";
    public static final String COL_LAST_MESSAGE_DIGEST = "last_message_digest";
    public static final String COL_MESSAGE_COUNT = "message_count";
    public static final String COL_DEFAULT_FLAG = "default_flag";
}
