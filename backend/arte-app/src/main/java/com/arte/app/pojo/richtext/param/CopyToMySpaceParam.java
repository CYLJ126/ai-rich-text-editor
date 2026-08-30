package com.arte.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 富文本资源复制到个人空间参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class CopyToMySpaceParam implements Serializable {

    @Serial
    private static final long serialVersionUID = -853635619113194079L;

    private Integer id;

    private Integer targetCatalogId;
}
