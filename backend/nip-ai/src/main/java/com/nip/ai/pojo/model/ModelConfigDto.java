package com.nip.ai.pojo.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.nip.core.cache.CacheTypeEnum;
import com.nip.core.cache.CacheTypeInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 模型配置实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:47 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@CacheTypeInfo(cacheType = CacheTypeEnum.DEFAULT_MODEL_CONFIG, cacheKeyType = String.class, cacheValueType = ModelConfigDto.class)
public class ModelConfigDto extends ModelConfigPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 7969875568973484222L;
    /**
     * 前端展示时脱敏 apiKey，不返回完整 key
     */
    @TableField(exist = false)
    private String maskedApiKey;
}
