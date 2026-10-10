package com.arte.app.service.rbac;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.arte.app.api.rbac.AbstractSecurityService;
import com.arte.app.api.rbac.OnlineService;
import com.arte.app.api.rbac.TokenService;
import com.arte.app.common.constant.RbacConstant;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.pojo.UserOnlineInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 15:47 ✾
 */
@Slf4j
@Service
public class TokenServiceImpl extends AbstractSecurityService implements TokenService {

    @Resource
    protected RedissonClient redissonClient;

    @Resource
    private OnlineService onlineService;

    private byte[] key;

    @Override
    public String createToken(Authentication authentication) {
        if (!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtUserDto session)
                || session.userOnlineInfo().getId() == null || session.userOnlineInfo().getAccountCreatedAt() == null) {
            throw new IllegalArgumentException("Verified account session required");
        }
        return JWT.create()
                .setPayload(RbacConstant.ARTE_USER_NAME, authentication.getName())
                .setPayload("accountId", session.userOnlineInfo().getId().toString())
                .setPayload("accountCreatedAt", session.userOnlineInfo().getAccountCreatedAt().toString())
                // 同名重注册或重新登录不能复用旧 token／Redis key。
                .setPayload("jti", UUID.randomUUID().toString())
                .setKey(key)
                .sign();
    }

    @Override
    public boolean verifyToken(String token) {
        try {
            return JWTUtil.verify(token, key);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    @Override
    public String getToken(HttpServletRequest request) {
        String token = request.getHeader(webSecurityProperties.getHeader());
        if (CharSequenceUtil.startWith(token, webSecurityProperties.getTokenStartWith())) {
            return CharSequenceUtil.subAfter(token, webSecurityProperties.getTokenStartWith(), false);
        }
        return CharSequenceUtil.EMPTY;
    }

    @Override
    public UserDetails getUserDetailsByToken(String token) {
        UserOnlineInfo online = onlineService.getOnlineInfo(token);
        if (online != null) online.setPassword(null);
        return online == null ? null : new JwtUserDto(online);
    }

    @Override
    public void renewal(String token, HttpServletRequest request) {
        RBucket<UserOnlineInfo> bucket = redissonClient.getBucket(webSecurityProperties.getOnlineKey() + token);
        if (bucket.isExists()) {
            bucket.expire(Duration.ofSeconds(webSecurityProperties.getTokenValidityInSeconds()));
        }
    }

    @Override
    @PostConstruct
    public void init() throws Exception {
        super.init();
        this.key = Base64.getDecoder().decode(webSecurityProperties.getBase64SecretKey());
    }

}
