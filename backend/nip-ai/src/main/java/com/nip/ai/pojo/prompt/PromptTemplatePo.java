package com.nip.ai.pojo.prompt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.ai.pojo.BaseDto;
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
@TableName("nip_ai_prompt_template")
public class PromptTemplatePo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = -3721973821478031218L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 模板名称（唯一键）
     */
    private String name;

    /**
     * 阶段: query_rewrite/sub_question/answer_gen/critique/rerank 等
     */
    private String stage;

    /**
     * Prompt 模板，支持{variable}占位符
     */
    private String template;

    /**
     * 变量说明
     */
    private String variables;

    private String description;

    private Boolean isActive;

    /**
     * 版本号，乐观锁
     */
    private Integer rowVersion;

    public static final String COL_ID = "id";

    public static final String COL_NAME = "name";

    public static final String COL_STAGE = "stage";

    public static final String COL_TEMPLATE = "template";

    public static final String COL_VARIABLES = "variables";

    public static final String COL_DESCRIPTION = "description";

    public static final String COL_IS_ACTIVE = "is_active";

    public static final String COL_ROW_VERSION = "row_version";
}
