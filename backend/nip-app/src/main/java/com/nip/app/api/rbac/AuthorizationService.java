package com.nip.app.api.rbac;

import org.springframework.security.core.GrantedAuthority;

import java.util.List;

/**
 * 授权相关
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 22:12 ✾
 */
public interface AuthorizationService extends SecurityService {

    List<GrantedAuthority> getAuthorities();
}
