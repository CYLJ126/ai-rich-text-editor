package com.arte.app.service.rbac;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.arte.app.api.rbac.AbstractSecurityService;
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

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 15:47 ✾
 */
@Slf4j
@Service
public class TokenServiceImpl extends AbstractSecurityService implements TokenService {

    @Resource
    protected RedissonClient redissonClient;

    private byte[] key;

    @Override
    public String createToken(Authentication authentication) {
        return JWT.create()
                .setPayload(RbacConstant.ARTE_USER_NAME, authentication.getName())
                .setKey(key)
                .sign();
    }

    @Override
    public boolean verifyToken(String token) {
        return JWTUtil.verify(token, key);
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
        RBucket<UserOnlineInfo> bucket = redissonClient.getBucket(webSecurityProperties.getOnlineKey() + token);
        return bucket.isExists() ? new JwtUserDto(bucket.get()) : null;
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
