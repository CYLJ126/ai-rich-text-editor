package com.nip.app.pojo.home.website;

import lombok.Data;

/**
 * 校验条件
 *
 * @author zhangsc
 * @since 2025/4/10 14:13
 */
@Data
public class ConditionConfig {
    /**
     * 校验值路径，如判断响应中的 errNo 是否为 0，为 0 则表示成功，才能进行解析
     */
    private String path;
    /**
     * 期望值，等于该值时，才进行解析
     */
    private String expectedValue;
}
