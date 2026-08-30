package com.nip.ai.pojo.conversation;

import com.nip.ai.common.enums.ConversationStatusEnum;
import com.nip.ai.common.enums.SceneTypeEnum;
import com.nip.ai.pojo.BaseParam;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话请求参数 Param
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/22 17:40 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ConversationParam extends BaseParam<ConversationDto> implements Serializable {
    @Serial
    private static final long serialVersionUID = -2673524604459015598L;

    /**
     * 关键词搜索
     */
    private String keyword;

    /**
     * 会话ID（查询参数）
     */
    private String convId;

    /**
     * 会话标题（查询参数）
     */
    private String title;

    /**
     * 会话状态（查询参数）
     */
    private ConversationStatusEnum status;

    /**
     * 会话场景（查询参数）
     */
    private SceneTypeEnum scene;

}
