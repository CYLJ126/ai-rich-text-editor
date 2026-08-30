package com.nip.ai.pojo.model;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一消息结构
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:56 ✾
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 4688502430237485073L;
    private String role;
    private String content;
    /**
     * 思考内容（仅assistant角色）
     */
    private String thinkContent;
}
