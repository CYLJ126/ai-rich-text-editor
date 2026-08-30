package com.nip.ai.pojo.model;

import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * 发送给模型的统一请求上下文
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:55 ✾
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRequest {
    private String provider;
    private String modelId;
    private String systemPrompt;
    private List<ModelMessage> messages;
    private Map<String, Object> params;
    /**
     * 是否启用流式
     */
    private boolean stream;
    /**
     * 是否启用网络搜索
     */
    private boolean enableSearch;
    /**
     * 是否启用思考模式
     */
    private boolean enableThinking;
    /**
     * 最大思考token数
     */
    private Integer thinkingBudget;
}
