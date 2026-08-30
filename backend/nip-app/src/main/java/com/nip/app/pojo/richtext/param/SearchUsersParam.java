package com.nip.app.pojo.richtext.param;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分享用户搜索参数
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class SearchUsersParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 7228802160335428099L;

    private String keyword;
}
