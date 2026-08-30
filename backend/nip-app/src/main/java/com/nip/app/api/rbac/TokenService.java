package com.nip.app.api.rbac;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Token 相关
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 15:47 ✾
 */
public interface TokenService extends SecurityService {

    /**
     * 创建 Token
     *
     * @param authentication 认证信息
     * @return Token
     */
    String createToken(Authentication authentication);

    /**
     * 验证 Token 合法性
     *
     * @param token Token
     * @return 是否合法
     */
    boolean verifyToken(String token);

    /**
     * 根据会话信息获取 Token
     *
     * @param request 会话信息
     * @return Token
     */
    String getToken(HttpServletRequest request);

    UserDetails getUserDetailsByToken(String token);

    /**
     * Token 续期
     *
     * @param token   Token
     * @param request http 请求
     */
    void renewal(String token, HttpServletRequest request) throws Exception;
}
