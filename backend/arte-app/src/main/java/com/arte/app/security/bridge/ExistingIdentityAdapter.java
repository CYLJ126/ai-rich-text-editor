package com.arte.app.security.bridge;

import com.arte.app.api.rbac.TokenService;
import com.arte.app.pojo.rbac.JwtUserDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 只在 HTTP 入口读取认证状态；异步执行通过已持久化的 ExecutionContext 传递身份。
 */
public class ExistingIdentityAdapter {
    private final TokenService tokens;
    private final JdbcSecurityRepository repository;

    public ExistingIdentityAdapter(TokenService tokens, JdbcSecurityRepository repository) {
        this.tokens = tokens;
        this.repository = repository;
    }

    public JdbcSecurityRepository.Account requireAccount(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) throw rejected();
        String token = tokens.getToken(request);
        if (token == null || token.isBlank() || !tokens.verifyToken(token)) throw rejected();
        var details = tokens.getUserDetailsByToken(token);
        if (!(details instanceof JwtUserDto jwt) || !details.isEnabled()
                || !authentication.getName().equals(details.getUsername())) throw rejected();
        var online = jwt.userOnlineInfo();
        var account = repository.accountByName(details.getUsername()).orElseThrow(ExistingIdentityAdapter::rejected);
        if (!account.enabled() || online.getId() == null || online.getId() != account.id()) throw rejected();
        return account;
    }

    private static org.springframework.security.access.AccessDeniedException rejected() {
        return new org.springframework.security.access.AccessDeniedException("arte.security.identity_rejected");
    }
}
