package com.arte.ai.pojo.prompt;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 提示词模板表
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_ai_prompt_template")
public class PromptTemplateDto extends PromptTemplatePo implements Serializable {

    @Serial
    private static final long serialVersionUID = -116251971399512057L;
}
