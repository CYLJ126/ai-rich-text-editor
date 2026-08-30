package com.nip.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 富文本资源发布到公共空间参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class PublishToPublicParam implements Serializable {

    @Serial
    private static final long serialVersionUID = -7259437916358794299L;

    private Integer id;

    private Integer targetCatalogId;
}
