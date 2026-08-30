package com.nip.ai.pojo.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.nip.ai.common.enums.*;
import com.nip.ai.pojo.assistant.AssistantDto;
import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.ai.pojo.model.ModelConfigDto;
import com.nip.core.enums.CurrencyEnum;
import com.nip.core.enums.TextTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聊天过程中的请求体流转对象
 * 包含请求模型的所有参数
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/18 10:43 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ChatRequestDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 3925323004164449198L;

    /**
     * 随机种子，保证可复现
     */
    private Integer seed = RandomUtil.randomInt();
    /**
     * 模型提供商
     */
    private ModelProviderEnum provider;
    /**
     * 模型 ID，表里的自键键
     */
    private Integer modelAutoId;
    /**
     * 模型 ID，传给大模型服务的标识
     */
    private String modelId;
    /**
     * 模型类型
     */
    private ModelTypeEnum modelType;
    /**
     * API Key（加密存储）
     */
    private String apiKey;
    /**
     * 自定义 API 地址
     */
    private String apiBaseUrl;
    /**
     * API 版本号
     */
    private String apiVersion;
    /**
     * 请求超时时间(秒)
     */
    private Integer timeoutSeconds;
    /**
     * 最大重试次数
     */
    private Integer maxRetries;
    /**
     * 代理，如 127.0.0.1:7897，不配则不使用代理，用户配 127.0.0.1 表示走服务器代理
     */
    private String proxy;
    /**
     * 系统提示词
     */
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
    private Double temperature;
    /**
     * 最大生成 token 数
     */
    private Integer maxTokens;
    /**
     * Top P 参数，0 ~ 1.0
     */
    private Double topP;
    /**
     * Top K 参数，0 ~ 100
     */
    private Integer topK;
    /**
     * 存在惩罚参数，-2.0 ~ 2.0
     */
    private Double presencePenalty;
    /**
     * 频率惩罚参数，-2.0 ~ 2.0
     */
    private Double frequencyPenalty;
    /**
     * 是否开启全局记忆功能
     */
    private Boolean globalMemoryFlag;
    /**
     * 额外参数
     */
    private Map<String, Object> extraParam = new HashMap<>();
    /**
     * 会话 ID
     */
    private String convId;
    /**
     * 场景
     */
    private SceneTypeEnum scene;
    /**
     * 交互类型
     */
    private InterActionTypeEnum interactionType;
    /**
     * 是否开启查询重写功能
     */
    private Boolean queryRewriteFlag;
    /**
     * 消息文本内容
     */
    private String content;
    /**
     * 引用的消息 ID
     */
    private String quotedMessageId;
    /**
     * 引用的消息
     */
    private MessageDto quotedMessage;
    /**
     * 附件 ID 列表
     */
    private List<String> attachmentIds;
    /**
     * 是否启用联网搜索
     */
    private Boolean enableSearch;
    /**
     * 是否启用视觉功能
     */
    private Boolean enableVision;
    /**
     * 是否启用深度思考
     */
    private Boolean enableThinking;
    /**
     * 是否为生成图片请求
     */
    private Boolean generateImage;
    /**
     * 关联知识库 ID 列表
     */
    private KnowledgeBaseTypeEnum knowledgeBaseId;
    /**
     * Rag 参数
     */
    private ChatRagRequestDto chatRagRequest;
    /**
     * 当前操作用户名
     */
    private String userName;
    /**
     * 输入单位价格，用于事后更新到库
     */
    private BigDecimal inputUnitPrice;
    /**
     * 输出单位价格，用于事后更新到库
     */
    private BigDecimal outputUnitPrice;
    /**
     * 货币单位，用于事后更新到库
     */
    private CurrencyEnum priceCurrency;
    /**
     * 生成类型
     */
    private GenerateTypeEnum generateType;
    /**
     * 生成字数上限
     */
    private Integer characterCountCeil;
    /**
     * 原文，如润色或翻译时的原内容
     */
    private String originalText;
    /**
     * 原语言
     */
    private String originalLanguage;
    /**
     * 翻译目标语言
     */
    private String targetLanguage;
    /**
     * 是否流式返回
     */
    private Boolean streamingFlag;
    /**
     * 默认模型配置
     */
    private ModelConfigDto defaultModelConfig;
    /**
     * 用户消息 ID，由前端生成
     */
    private String userMessageId;
    /**
     * 助手消息 ID，由前端生成
     */
    private String assistantMessageId;

    /**
     * 是否为重新生成请求
     */
    private Boolean regenerate = false;

    public ChatRequestDto fillModelConfig(ModelConfigDto modelConfig) {
        if (modelConfig == null) {
            return this;
        }
        this.provider = modelConfig.getProvider();
        this.modelAutoId = modelConfig.getId();
        this.modelId = modelConfig.getModelId();
        this.modelType = modelConfig.getModelType();
        this.apiKey = modelConfig.getApiKey();
        this.apiBaseUrl = modelConfig.getApiBaseUrl();
        this.apiVersion = modelConfig.getApiVersion();
        this.maxTokens = modelConfig.getMaxTokens();
        this.timeoutSeconds = modelConfig.getTimeoutSeconds();
        this.maxRetries = modelConfig.getMaxRetries();
        this.proxy = modelConfig.getProxy();
        if (CollUtil.isNotEmpty(modelConfig.getDefaultParam())) {
            this.extraParam.putAll(modelConfig.getDefaultParam());
        }
        this.inputUnitPrice = modelConfig.getInputUnitPrice();
        this.outputUnitPrice = modelConfig.getOutputUnitPrice();
        this.priceCurrency = modelConfig.getPriceCurrency();
        return this;
    }

    public ChatRequestDto fillAssistantConfig(AssistantDto assistantConfig) {
        if (assistantConfig == null) {
            return this;
        }
        this.systemPrompt = assistantConfig.getSystemPrompt();
        this.contextStrategy = assistantConfig.getContextStrategy();
        this.queryRewriteFlag = assistantConfig.getQueryRewriteFlag();
        this.contextWindow = assistantConfig.getContextWindow();
        this.reasoningEffort = assistantConfig.getReasoningEffort();
        this.textType = assistantConfig.getTextType();
        if (Objects.nonNull(assistantConfig.getTemperature())) {
            this.temperature = assistantConfig.getTemperature().doubleValue();
        }
        this.maxTokens = assistantConfig.getMaxTokens();
        if (Objects.nonNull(assistantConfig.getTopP())) {
            this.topP = assistantConfig.getTopP().doubleValue();
        }
        this.topK = assistantConfig.getTopK();
        if (Objects.nonNull(assistantConfig.getPresencePenalty())) {
            this.presencePenalty = assistantConfig.getPresencePenalty().doubleValue();
        }
        if (Objects.nonNull(assistantConfig.getFrequencyPenalty())) {
            this.frequencyPenalty = assistantConfig.getFrequencyPenalty().doubleValue();
        }
        this.globalMemoryFlag = assistantConfig.getGlobalMemoryFlag();
        this.knowledgeBaseId = assistantConfig.getKnowledgeBaseId();
        if (CollUtil.isNotEmpty(assistantConfig.getExtraParam())) {
            this.extraParam.putAll(assistantConfig.getExtraParam());
        }
        return this;
    }

    public ChatRequestDto fillConversationConfig(ConversationDto conversationConfig) {
        if (conversationConfig == null) {
            return this;
        }
        this.convId = conversationConfig.getConvId();
        this.systemPrompt = conversationConfig.getSystemPrompt();
        this.knowledgeBaseId = conversationConfig.getKnowledgeBaseId();
        this.queryRewriteFlag = conversationConfig.getQueryRewriteFlag();
        this.contextStrategy = conversationConfig.getContextStrategy();
        this.contextWindow = conversationConfig.getContextWindow();
        this.reasoningEffort = conversationConfig.getReasoningEffort();
        this.textType = conversationConfig.getTextType();
        if (Objects.nonNull(conversationConfig.getTemperature())) {
            this.temperature = conversationConfig.getTemperature().doubleValue();
        }
        this.maxTokens = conversationConfig.getMaxTokens();
        this.topK = conversationConfig.getTopK();
        if (Objects.nonNull(conversationConfig.getTopP())) {
            this.topP = conversationConfig.getTopP().doubleValue();
        }
        if (Objects.nonNull(conversationConfig.getPresencePenalty())) {
            this.presencePenalty = conversationConfig.getPresencePenalty().doubleValue();
        }
        if (Objects.nonNull(conversationConfig.getFrequencyPenalty())) {
            this.frequencyPenalty = conversationConfig.getFrequencyPenalty().doubleValue();
        }
        this.globalMemoryFlag = conversationConfig.getGlobalMemoryFlag();
        this.scene = conversationConfig.getScene();
        this.interactionType = conversationConfig.getInteractionType();
        if (CollUtil.isNotEmpty(conversationConfig.getExtraParam())) {
            this.extraParam.putAll(conversationConfig.getExtraParam());
        }
        return this;
    }

    public ChatRequestDto fillChatRequestParam(ChatRequestParam chatRequestParam) {
        if (chatRequestParam == null) {
            return this;
        }
        this.content = chatRequestParam.getContent();
        this.quotedMessageId = chatRequestParam.getQuotedMessageId();
        this.attachmentIds = chatRequestParam.getAttachmentIds();
        this.enableSearch = chatRequestParam.getEnableSearch();
        this.enableVision = chatRequestParam.getEnableVision();
        this.enableThinking = chatRequestParam.getEnableThinking();
        this.generateImage = chatRequestParam.getGenerateImage();
        if (Objects.nonNull(chatRequestParam.getReasoningEffort())) {
            this.reasoningEffort = chatRequestParam.getReasoningEffort();
        }
        this.chatRagRequest = chatRequestParam.getChatRagRequest();
        if (Objects.nonNull(this.chatRagRequest)) {
            this.knowledgeBaseId = this.chatRagRequest.getKnowledgeBaseType();
        }
        this.userName = chatRequestParam.getUserName();
        this.generateType = chatRequestParam.getGenerateType();
        this.scene = chatRequestParam.getScene();
        this.originalText = chatRequestParam.getOriginalText();
        this.originalLanguage = chatRequestParam.getOriginalLanguage();
        this.targetLanguage = chatRequestParam.getTargetLanguage();
        this.characterCountCeil = chatRequestParam.getCharacterCountCeil();
        this.userMessageId = chatRequestParam.getUserMessageId();
        this.assistantMessageId = chatRequestParam.getAssistantMessageId();
        return this;
    }

}
