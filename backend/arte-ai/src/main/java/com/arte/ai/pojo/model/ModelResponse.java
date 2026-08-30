package com.arte.ai.pojo.model;

import lombok.*;

/**
 * 模型响应统一结构
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:57 ✾
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelResponse {
    private String content;
    private String thinkContent;
    private String finishReason;
    private String requestId;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer thinkTokens;
    private boolean success;
    private String errorCode;
    private String errorMessage;
}
