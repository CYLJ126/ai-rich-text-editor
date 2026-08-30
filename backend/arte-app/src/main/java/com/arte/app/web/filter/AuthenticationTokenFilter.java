package com.arte.app.web.filter;

import com.arte.app.api.rbac.OnlineService;
import com.arte.app.api.rbac.TokenService;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.core.pojo.UserContext;
import com.arte.core.utils.LogUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * token 认证
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 16:31 ✾
 */
@Slf4j
@Service
public class AuthenticationTokenFilter extends OncePerRequestFilter {

    @Resource
    private TokenService tokenService;

    @Resource
    private OnlineService onlineService;

    @Resource
    private WebSecurityProperties webSecurityProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        LogUtil.setIdIfNull();
        // 从 header 获取 Token：Authorization: Bearer <token>
        String authHeader = request.getHeader(webSecurityProperties.getHeader());
        if (Objects.isNull(authHeader) || !authHeader.startsWith(webSecurityProperties.getTokenStartWith())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authToken = authHeader.split(" ")[1];
        // verify token
        if (!tokenService.verifyToken(authToken)) {
            log.info("非法 token：{}", authToken);
            filterChain.doFilter(request, response);
            return;
        }

        UserDetails userDetails = tokenService.getUserDetailsByToken(authToken);
        if (Objects.nonNull(userDetails)) {
            // 注意，这里使用的是3个参数的构造方法，此构造方法将认证状态设置为true
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails.getUsername(), userDetails.getPassword(), userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            // 将认证过的凭证保存到security的上下文中以便于在程序中使用
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // Token 续期
            try {
                tokenService.renewal(authToken, request);
            } catch (Exception e) {
                log.error("Token 【{}】续期失败", authToken, e);
            }
            UserContext.setUserOnlineInfo(onlineService.getOnlineInfo(authToken));
        }

        filterChain.doFilter(request, response);
    }
}
