package com.nip.ai.pojo.conversation;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话列表排序更新请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:51 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ConversationSortDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 5974376689962008955L;
    /**
     * 会话ID
     */
    @NotBlank
    private String convId;
    /**
     * 排序值
     */
    private Long sortOrder;
}
