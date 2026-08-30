package com.nip.app.config.bean;

import lombok.Data;

/**
 * 认证参数配置
 *
 * @author zhangsc
 */
@Data
public class WebSecurityProperties {

    /**
     * Request Headers ： Authorization
     */
    private String header;

    /**
     * 令牌前缀，最后留个空格 Bearer
     */
    private String tokenStartWith;

    /**
     * 生成令牌的 key
     */
    private String base64SecretKey;

    /**
     * 令牌过期时间 此处单位/毫秒
     */
    private Long tokenValidityInSeconds;

    /**
     * 在线用户 key，根据 key 查询 redis 中在线用户的数据
     */
    private String onlineKey;

    /**
     * 验证码 key
     */
    private String codeKey;

    /**
     * token 续期检查
     */
    private Long detect;

    /**
     * 续期时间
     */
    private Long renew;

    /**
     * 默认密码
     */
    private String defaultPassword;

    public String getTokenStartWith() {
        return tokenStartWith + " ";
    }
}
