package com.nip.ai.pojo.conversation;

import com.nip.ai.common.enums.ContextStrategyEnum;
import com.nip.ai.common.enums.ConversationStatusEnum;
import com.nip.ai.common.enums.KnowledgeBaseTypeEnum;
import com.nip.ai.common.enums.ReasoningEffortEnum;
import com.nip.core.enums.TextTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 会话创建/更新请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:50 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ConversationUpsertDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -1984761828813421623L;

    /**
     * 会话 ID
     */
    private String convId;

    /**
     * 会话标题
     */
    private String title;
    /**
     * 助手ID
     */
    private Integer assistantId;
    /**
     * 模型 ID，注意，是表记录的 ID 字段，不是模型配置表的 model_id 字段
     */
    private Integer modelId;
    /**
     * 模型参数
     */
    private Map<String, Object> extraParam;
    /**
     * 系统提示词
     */
    private String systemPrompt;
    /**
     * 知识库
     */
    private KnowledgeBaseTypeEnum knowledgeBaseId;
    /**
     * 上下文策略：WINDOW/SUMMARY/FULL
     */
    private ContextStrategyEnum contextStrategy;
    /**
     * 上下文窗口大小
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
     * Top K 参数
     */
    private BigDecimal topK;
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
}
