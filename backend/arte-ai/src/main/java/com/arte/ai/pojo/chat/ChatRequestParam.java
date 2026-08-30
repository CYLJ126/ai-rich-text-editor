package com.arte.ai.pojo.chat;

import com.arte.ai.common.enums.GenerateTypeEnum;
import com.arte.ai.common.enums.ReasoningEffortEnum;
import com.arte.ai.common.enums.SceneTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 发起聊天请求 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:47 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ChatRequestParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 2995430259373689150L;
    /**
     * 会话 ID（为空则自动创建）
     */
    private String convId;
    /**
     * 用户消息内容
     */
    private String content;
    /**
     * 模型 ID，覆盖会话设置
     */
    private Integer modelId;
    /**
     * 引用的消息ID
     */
    private String quotedMessageId;
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
     * 是否生成图片
     */
    private Boolean generateImage;
    /**
     * 推理力度；流式生成未设置时默认为 none
     */
    private ReasoningEffortEnum reasoningEffort;
    /**
     * Rag 参数
     */
    private ChatRagRequestDto chatRagRequest;
    /**
     * 当前操作用户名
     */
    private String userName;
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
     * 场景
     */
    private SceneTypeEnum scene;
    /**
     * 是否流式返回
     */
    private Boolean streamingFlag;
    /**
     * 用户消息 ID，由前端生成
     */
    private String userMessageId;
    /**
     * 助手消息 ID，由前端生成
     */
    private String assistantMessageId;

}
