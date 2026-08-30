package com.nip.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 文章批量移动参数
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ArticleBatchMoveParam implements Serializable {

    @Serial
    private static final long serialVersionUID = -7177972721194143101L;

    /**
     * 文章ID列表
     */
    private List<Integer> articleIds;

    /**
     * 目标目录ID
     */
    private Integer catalogId;
}
