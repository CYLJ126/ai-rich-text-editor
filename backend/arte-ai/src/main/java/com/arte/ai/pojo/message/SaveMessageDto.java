package com.arte.ai.pojo.message;

import com.arte.ai.common.enums.MessageStatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 保存消息消息 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:49 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class SaveMessageDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 5723804926578258487L;
    /**
     * 消息ID（前端生成）
     */
    private String messageId;
    /**
     * 父消息ID
     */
    private String parentMessageId;
    /**
     * 分支ID
     */
    private String branchId;
    /**
     * 消息内容
     */
    private String content;
    /**
     * 思考内容
     */
    private String reasoningContent;
    /**
     * 模型ID
     */
    private Integer modelId;
    /**
     * 消息状态
     */
    private MessageStatusEnum status;
    /**
     * 结束原因
     */
    private String finishReason;
    /**
     * 提示词 token 数
     */
    private Integer promptToken;
    /**
     * 补全 token 数
     */
    private Integer completionToken;
    /**
     * 思考 token 数
     */
    private Integer reasoningToken;
    /**
     * 总 token 数
     */
    private Integer totalToken;
    /**
     * 首 token 延迟(ms)
     */
    private Integer firstTokenMs;
    /**
     * 总延迟(ms)
     */
    private Integer latencyMs;
    /**
     * 错误码
     */
    private String errorCode;
    /**
     * 错误信息
     */
    private String errorMessage;
    /**
     * 请求ID
     */
    private String requestId;
    /**
     * 引用消息ID
     */
    private String quotedMessageId;
    /**
     * 附件ID列表
     */
    private List<String> attachmentIds;
    /**
     * 当前用户
     */
    private String userName;
}
