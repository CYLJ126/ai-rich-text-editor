package com.nip.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 富文本资源分享对象参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ShareTargetParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 133390177373736337L;

    private String resourceType;

    private Integer resourceId;
}
