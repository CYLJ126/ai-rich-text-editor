package com.nip.ai.pojo.model;

import lombok.*;

/**
 * 流式响应 chunk
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:57 ✾
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelStreamChunk {
    /**
     * 增量内容
     */
    private String deltaContent;
    /**
     * 增量思考内容
     */
    private String deltaThinkContent;
    /**
     * 是否是最后一个chunk
     */
    private boolean done;
    /**
     * finish reason（done=true时有值）
     */
    private String finishReason;
    /**
     * 请求ID
     */
    private String requestId;
    /**
     * token用量（done=true时有值）
     */
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer thinkTokens;
    /**
     * 错误信息
     */
    private String errorCode;
    private String errorMessage;
}

