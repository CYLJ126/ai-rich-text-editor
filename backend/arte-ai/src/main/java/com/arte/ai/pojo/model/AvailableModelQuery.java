package com.arte.ai.pojo.model;

import com.arte.ai.common.enums.ModelProviderEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 查询模型提供商可用模型的参数。
 */
@Getter
@Setter
@Accessors(chain = true)
public class AvailableModelQuery {
    private ModelProviderEnum provider;
    private Integer modelConfigId;
    private String apiKey;
    private String apiBaseUrl;
}
