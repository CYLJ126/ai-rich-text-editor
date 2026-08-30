package com.nip.app.api.rbac;

import com.nip.core.pojo.UserOnlineInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Map;

/**
 * 用户在线管理
 *
 * @author zhangsc
 * @since 2025/2/5 17:35
 */
public interface OnlineService extends SecurityService {

    /**
     * 登录时缓存在线信息
     *
     * @param authentication 认证信息
     * @param token          当前活跃 token
     * @param request        http请求
     */
    void saveOnlineInfo(Authentication authentication, String token, HttpServletRequest request) throws Exception;

    /**
     * 登录时保证单点登录，踢掉其他在线登录
     *
     * @param authentication 认证信息
     * @param currentToken   当前活跃 token，这个不踢
     */
    void ensureSingleOnline(Authentication authentication, String currentToken);

    /**
     * 根据用户名查询在线信息
     * 会同时添加所拥有的角色下的菜单、操作权限
     *
     * @param token Token
     * @return 在线信息
     */
    UserOnlineInfo getOnlineInfo(String token);

    /**
     * 根据用户名查询在线信息
     *
     * @param name 用户名
     * @return 在线信息
     */
    Map<String, UserOnlineInfo> listOnlineInfo(String name);
}
