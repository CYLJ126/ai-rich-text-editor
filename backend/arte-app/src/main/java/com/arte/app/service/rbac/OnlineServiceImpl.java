package com.arte.app.service.rbac;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.arte.app.api.rbac.AbstractSecurityService;
import com.arte.app.api.rbac.OnlineService;
import com.arte.app.api.rbac.RoleService;
import com.arte.app.api.rbac.UserService;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.pojo.rbac.RoleDto;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.utils.IpUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanParams;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 用户在线信息服务
 *
 * @author zhangsc
 * @since 2025/2/5 17:36
 */
@Slf4j
@Service
public class OnlineServiceImpl extends AbstractSecurityService implements OnlineService {

    @Resource
    protected RedissonClient redissonClient;

    @Resource
    private UserService userService;

    @Resource
    private RoleService roleService;

    @Override
    public void saveOnlineInfo(Authentication authentication, String token, HttpServletRequest request) throws Exception {
        UserAgent userAgent = UserAgentUtil.parse(request.getHeader("User-Agent"));
        JwtUserDto jwtUserDto = (JwtUserDto) authentication.getPrincipal();
        UserOnlineInfo userOnline = jwtUserDto.userOnlineInfo();
        userOnline.setIp(IpUtil.getHostIp())
                .setBrowser(userAgent.getBrowser().getName())
                .setLoginTime(new Date());
        RBucket<UserOnlineInfo> bucket = redissonClient.getBucket(getKey(token));
        bucket.set(userOnline, Duration.ofSeconds(webSecurityProperties.getTokenValidityInSeconds()));
    }

    @Override
    public void ensureSingleOnline(Authentication authentication, String currentToken) {
        JwtUserDto jwtUserDto = (JwtUserDto) authentication.getPrincipal();
        Map<String, UserOnlineInfo> map = listOnlineInfo(jwtUserDto.getUsername());
        for (Map.Entry<String, UserOnlineInfo> entry : map.entrySet()) {
            String key = entry.getKey();
            try {
                if (!CharSequenceUtil.equals(key, getKey(currentToken))) {
                    redissonClient.getBucket(key).deleteAsync();
                }
            } catch (Exception e) {
                log.error("用户名【{}】Token【{}】清除其他在线用户时出错，跳过处理", jwtUserDto.getUsername(), key, e);
            }
        }
    }

    @Override
    public UserOnlineInfo getOnlineInfo(String token) {
        UserOnlineInfo userOnlineInfo = (UserOnlineInfo) redissonClient.getBucket(getKey(token)).get();
        if (userOnlineInfo == null) {
            return null;
        }
        UserDto currentUserCache = userService.getByName(userOnlineInfo.getUserName());
        if (Objects.nonNull(currentUserCache)) {
            userOnlineInfo.setMenus(currentUserCache.getMenus());
            userOnlineInfo.setRoles(currentUserCache.getRoles());
            userOnlineInfo.setMenuOperations(currentUserCache.getMenuOperations());
        }
        if (CollUtil.isNotEmpty(userOnlineInfo.getRoles())) {
            // 加入角色所拥有的菜单、操作权限
            userOnlineInfo.getRoles().forEach(roleCode -> {
                RoleDto role = roleService.getRoleByCode(roleCode);
                userOnlineInfo.setMenus(new HashSet<>(CollUtil.union(role.getMenus(), userOnlineInfo.getMenus())));
                userOnlineInfo.setMenuOperations(new HashSet<>(CollUtil.union(role.getMenuOperations(), userOnlineInfo.getMenuOperations())));
            });
        }
        return userOnlineInfo;
    }

    @Override
    public Map<String, UserOnlineInfo> listOnlineInfo(String name) {
        Map<String, UserOnlineInfo> map = new HashMap<>();
        KeysScanParams scanParams = new KeysScanParams();
        scanParams.pattern(getKey("*"));
        Iterable<String> keys = redissonClient.getKeys().getKeys(scanParams);
        for (String key : keys) {
            RBucket<UserOnlineInfo> bucket = redissonClient.getBucket(key);
            if (bucket.isExists()) {
                UserOnlineInfo userOnline = bucket.get();
                if (CharSequenceUtil.equals(name, userOnline.getUserName())) {
                    map.put(key, userOnline);
                }
            }
        }
        return map;
    }

    private String getKey(String token) {
        return webSecurityProperties.getOnlineKey() + token;
    }
}
