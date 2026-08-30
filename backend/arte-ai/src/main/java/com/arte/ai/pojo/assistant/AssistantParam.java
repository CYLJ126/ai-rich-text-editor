package com.arte.ai.pojo.assistant;

import com.arte.ai.pojo.BaseParam;
import com.arte.core.enums.StatusEnum;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 助手查询参数
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/13 20:43 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@NoArgsConstructor
public class AssistantParam extends BaseParam<AssistantDto> implements Serializable {
    @Serial
    private static final long serialVersionUID = 8830000874093169047L;

    /**
     * 助手名称
     */
    private String name;

    /**
     * 状态: 1-启用；3-禁用；
     */
    private StatusEnum status;

    /**
     * 置顶状态
     */
    private Boolean pinFlag;

    /**
     * 助手描述
     */
    private String description;
    /**
     * 是否默认模型
     */
    private Boolean defaultFlag;
}
