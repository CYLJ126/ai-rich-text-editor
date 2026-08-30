package com.nip.app.web.security;

import cn.hutool.core.collection.CollUtil;
import com.nip.core.pojo.UserContext;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 22:29 ✾
 */
@Service(value = "pcs")
public class PermissionCheckService {

    public Boolean check(String... permissions) {
        // 获取当前用户的所有权限，也可以从 SecurityContextHolder.getContext().getAuthentication() 中获取
        Collection<String> userPermissions = UserContext.getUserOnlineInfo().getMenuOperations();
        if (CollUtil.isEmpty(userPermissions)) {
            return Boolean.FALSE;
        }
        // 判断当前用户的所有权限是否包含接口上定义的权限
        return userPermissions.contains("admin") || Arrays.stream(permissions).anyMatch(userPermissions::contains);
    }
}
