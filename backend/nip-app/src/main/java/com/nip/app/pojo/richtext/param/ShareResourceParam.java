package com.nip.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分享富文本资源参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ShareResourceParam implements Serializable {

    @Serial
    private static final long serialVersionUID = -7799373066766792608L;

    private String resourceType;

    private Integer resourceId;

    private String targetType;

    private List<String> targetUsers;

    private List<String> targetRoles;

    private String permission;

    private String articlePermission;
}
