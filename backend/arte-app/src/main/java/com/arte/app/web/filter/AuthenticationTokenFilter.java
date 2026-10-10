package com.arte.app.web.filter;

import com.arte.app.api.rbac.TokenService;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.pojo.UserContext;
import com.arte.core.utils.LogUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 基于 Bearer Token 建立当前 HTTP 请求的可信登录身份。
 * <p>
 * 借助 {@link SecurityContextRepository} 支持异步分发恢复认证。
 * <p>
 * 先验证 Token 签名，再由 {@link TokenService} 读取并核验在线会话与当前账户，
 * 将认证结果写入 Spring Security 上下文，并为同步旧业务设置 {@link UserContext}。
 * 本过滤器负责入口身份认证；租户、工作空间及具体资源的访问资格由后续业务授权判断。
 * <p>
 * 缺少或无效的 Token 不建立认证，由后续安全链决定匿名访问或拒绝访问。
 * 账号数据库不可用时返回 503，避免将无法核验的身份视为有效身份。
 * 同一次请求的异步分发通过保存的 SecurityContext 恢复认证；UserContext 只在当前线程内有效，
 * 不自动传播到 Reactor 或异步任务，并在请求离开本过滤器时清理。
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
    private WebSecurityProperties webSecurityProperties;

    /**
     * 显式保存认证上下文，供同一次请求的后续 ASYNC 分发恢复身份
     */
    @Resource
    private SecurityContextRepository securityContextRepository;

    /**
     * 管理当前请求的认证及旧用户上下文生命周期。
     *
     * <p>常规请求执行一次；沿用 OncePerRequestFilter 默认行为，异步分发不重复验证 Token。
     * authenticate 若已提交错误响应，则不再调用后续过滤器；否则继续由安全链处理访问资格。
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 后续过滤器处理失败
     * @throws IOException      写入响应或后续过滤器处理时发生 I/O 错误
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // Servlet 工作线程会复用，先清除可能遗留的旧用户信息，避免跨请求串用身份。
        UserContext.clear();
        LogUtil.setIdIfNull();
        try {
            authenticate(request, response);
            if (!response.isCommitted()) filterChain.doFilter(request, response);
        } finally {
            // 即使未进入 Controller、访问被拒绝或已启动异步响应，也必须清理当前线程的旧上下文。
            // Spring Security 上下文的线程清理由其安全链负责，异步身份通过请求中保存的上下文恢复。
            UserContext.clear();
        }
    }

    /**
     * 解析并核验 Bearer Token，通过后建立认证上下文。
     * <p>
     * 无有效会话时直接返回，不在这里决定接口是否允许匿名访问。
     * 在线身份核验委托 TokenService 完成，包括当前账号状态、会话主键、名称与账户创建时间的一致性；
     * 不仅依赖 Redis 中保存的历史登录状态。
     *
     * @param request  提供认证头及请求详情的当前请求
     * @param response 账号权威不可用时用于提交 503 响应
     * @throws IOException 提交错误响应时发生 I/O 错误
     */
    private void authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 按配置读取认证头与前缀，例如 Authorization: Bearer <token>；未提供则保持未认证状态。
        String authHeader = request.getHeader(webSecurityProperties.getHeader());
        if (Objects.isNull(authHeader) || !authHeader.startsWith(webSecurityProperties.getTokenStartWith())) {
            return;
        }

        String authToken = authHeader.substring(webSecurityProperties.getTokenStartWith().length()).trim();
        // 空 Token 或包含内部空白的格式直接拒绝解析，避免进入签名验证或在线身份查询。
        if (authToken.isEmpty() || authToken.chars().anyMatch(Character::isWhitespace)) return;
        // 签名有效只说明 Token 未被篡改；会话是否存在、账户是否仍有效还需继续核验。
        if (!tokenService.verifyToken(authToken)) {
            return;
        }

        JwtUserDto userDetails;
        try {
            // 复用统一在线身份核验结果；保留账户主键，不再按用户名另查一份在线用户信息。
            var details = tokenService.getUserDetailsByToken(authToken);
            if (!(details instanceof JwtUserDto session) || !session.isEnabled() || session.userOnlineInfo().getId() == null) {
                return;
            }
            userDetails = session;
        } catch (DataAccessException unavailable) {
            // 无法确认当前账号状态时拒绝认证；不记录凭据或底层 SQL 参数。
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Account authority unavailable");
            return;
        }
        // 三参数构造器表示已认证，因此只能在前面的核验通过后使用。
        // principal 保留账户身份和既有 RBAC 权限，credentials 置空，不把登录密码带入请求认证。
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        // 为当前请求创建独立上下文；本项目的 Repository 保存到请求属性中，供 Mono／SSE 的 ASYNC 分发恢复。
        // 后续分发恢复的是同一次请求的认证，不会因此为其他请求创建登录会话。
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
        try {
            // 仅为已通过核验的在线会话续期；续期失败不撤销本次已完成的认证，也不输出 Token 本身
            tokenService.renewal(authToken, request);
        } catch (Exception e) {
            log.warn("Token renewal failed, type={}", e.getClass().getSimpleName());
        }
        // 兼容依赖 ThreadLocal 的同步旧业务和审计逻辑；离开 doFilterInternal 时统一清理。
        UserContext.setUserOnlineInfo(userDetails.userOnlineInfo());
    }
}
