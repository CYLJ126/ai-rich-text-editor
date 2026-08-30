package com.nip.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 富文本资源公开状态切换参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class PublicToggleParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 3521426719621178745L;

    private Integer id;

    private Boolean isPublic;

    private Integer targetCatalogId;
}
