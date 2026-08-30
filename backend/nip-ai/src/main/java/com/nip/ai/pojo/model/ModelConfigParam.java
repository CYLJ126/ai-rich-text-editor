package com.nip.ai.pojo.model;

import com.nip.ai.common.enums.ModelProviderEnum;
import com.nip.ai.pojo.BaseParam;
import com.nip.core.enums.StatusEnum;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型配置查询参数
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/13 20:43 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@NoArgsConstructor
public class ModelConfigParam extends BaseParam<ModelConfigDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -3087083799323115993L;
    /**
     * 模型提供商
     */
    private ModelProviderEnum provider;
    /**
     * 模型ID
     */
    private String modelId;
    /**
     * 模型显示名称
     */
    private String modelName;
    /**
     * 模型类型：CHAT/EMBEDDING/IMAGE/AUDIO
     */
    private String modelType;
    /**
     * 状态: 1-启用；3-禁用
     */
    private StatusEnum status;
    /**
     * 置顶状态
     */
    private Boolean pinFlag;
    /**
     * 是否默认模型
     */
    private Boolean defaultFlag;
}
