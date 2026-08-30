package com.arte.app.web.security;

import com.arte.app.pojo.rbac.JwtUserDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 提供认证服务
 * TODO 删除，换回 DaoAuthenticationProvider
 *
 * @author zhangsc
 * @since 2025/2/5 14:47
 */
@Slf4j
public class JwtAuthenticationProvider implements AuthenticationProvider {

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private UserDetailsService userService;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        JwtUserDto jwtUserDto = (JwtUserDto) authentication.getPrincipal();
        String password = String.valueOf(authentication.getCredentials());

        UserDetails userDetails = userService.loadUserByUsername(jwtUserDto.getUsername());
        if (passwordEncoder.matches(password, userDetails.getPassword())) {
            return new UsernamePasswordAuthenticationToken(jwtUserDto.getUsername(), password, userDetails.getAuthorities());
        }

        throw new BadCredentialsException("认证失败!");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.equals(authentication);
    }
}
