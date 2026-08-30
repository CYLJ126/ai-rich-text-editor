package com.arte.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 取消富文本资源分享参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class UnshareResourceParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 3754250253138330034L;

    private String resourceType;

    private Integer resourceId;

    private String targetType;

    private String targetUser;

    private String targetRole;
}
