package com.nip.ai.pojo.assistant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.nip.ai.common.enums.ContextStrategyEnum;
import com.nip.ai.common.enums.KnowledgeBaseTypeEnum;
import com.nip.ai.common.enums.ReasoningEffortEnum;
import com.nip.ai.pojo.BaseDto;
import com.nip.core.enums.StatusEnum;
import com.nip.core.enums.TextTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.ibatis.type.JdbcType;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * AI 助手实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:39 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName(value = "nip_ai_assistant", autoResultMap = true)
public class AssistantPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 2317928027276986253L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 助手名称
     */
    private String name;

    /**
     * 头像 icon
     */
    private String avatar;

    /**
     * 系统提示词
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String systemPrompt;
    /**
     * 上下文策略
     */
    private ContextStrategyEnum contextStrategy;

    /**
     * 上下文窗口
     */
    private Integer contextWindow;

    /**
     * 默认模型 ID
     */
    private Integer modelId;

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
     * Top P 参数，0 ~ 1.0
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
     * 额外参数
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extraParam;

    /**
     * 关联知识库 ID 列表
     */
    private KnowledgeBaseTypeEnum knowledgeBaseId;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 状态: 1-启用；3-禁用；
     */
    private StatusEnum status;

    /**
     * 置顶状态
     */
    private Boolean pinFlag;

    /**
     * 是否开启查询重写功能
     */
    private Boolean queryRewriteFlag;

    /**
     * 是否默认助手
     */
    private Boolean defaultFlag;

    /**
     * 助手描述
     */
    private String description;

    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_AVATAR = "avatar";
    public static final String COL_SYSTEM_PROMPT = "system_prompt";
    public static final String COL_CONTEXT_STRATEGY = "context_strategy";
    public static final String COL_CONTEXT_WINDOW = "context_window";
    public static final String COL_MODEL_ID = "model_id";
    public static final String COL_REASONING_EFFORT = "reasoning_effort";
    public static final String COL_TEXT_TYPE = "text_type";
    public static final String COL_TEMPERATURE = "temperature";
    public static final String COL_MAX_TOKENS = "max_tokens";
    public static final String COL_TOP_P = "top_p";
    public static final String COL_TOP_K = "top_k";
    public static final String COL_PRESENCE_PENALTY = "presence_penalty";
    public static final String COL_FREQUENCY_PENALTY = "frequency_penalty";
    public static final String COL_GLOBAL_MEMORY_FLAG = "global_memory_flag";
    public static final String COL_EXTRA_PARAM = "extra_param";
    public static final String COL_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    public static final String COL_SORT_ORDER = "sort_order";
    public static final String COL_STATUS = "status";
    public static final String COL_PIN_FLAG = "pin_flag";
    public static final String COL_QUERY_REWRITE_FLAG = "query_rewrite_flag";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_DEFAULT_FLAG = "default_flag";
}
