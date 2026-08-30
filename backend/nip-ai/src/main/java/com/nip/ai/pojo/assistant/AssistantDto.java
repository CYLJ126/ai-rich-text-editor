package com.nip.ai.pojo.assistant;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 助手实体
 *
 * @author zhangsc
 * @since 2026-07-13
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class AssistantDto extends AssistantPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -6784146959412655352L;

}
