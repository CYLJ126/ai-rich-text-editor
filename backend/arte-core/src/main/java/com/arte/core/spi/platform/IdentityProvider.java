package com.arte.core.spi.platform;

import com.arte.core.pojo.platform.Principal;

import java.util.Optional;

/**
 * 身份环境端口
 * <p>
 * 登录名必须已认证；从现有用户主键映射稳定主体，改名不换 ID；冲突拒绝且注销映射不重绑。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface IdentityProvider {

    /**
     * 将已认证的登录名解析为稳定主体及其当前状态，无法解析时返回空。
     */
    Optional<Principal> resolveAuthenticatedName(String authenticatedName);

    /**
     * Worker／重检按稳定主体读取当前状态，不按保存的旧用户名查同名账户。
     */
    Optional<Principal> findBySubjectId(String subjectId);

    /**
     * 在当前事务中读取稳定主体的最新状态，不使用旧事务快照、缓存或只读副本。
     * 必须参与调用方原事务，不另开事务或等待远端身份服务；不能默认委托普通查询。
     * 调用方先取得主体门控，再取得租户／空间门控。
     */
    Optional<Principal> findCurrentBySubjectId(String subjectId);
}
