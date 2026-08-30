package com.arte.ai.pojo.conversation;

import com.baomidou.mybatisplus.annotation.TableField;
import com.arte.ai.common.enums.ConversationSortTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 会话 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:41 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ConversationDto extends ConversationPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 5816921966170232273L;
    /**
     * 排序方式（查询参数）
     */
    @TableField(exist = false)
    private ConversationSortTypeEnum sortType;
    /**
     * 关键词搜索
     */
    @TableField(exist = false)
    private String keyword;
    /**
     * 助手名称（查询返回）
     */
    @TableField(exist = false)
    private String assistantName;
}
