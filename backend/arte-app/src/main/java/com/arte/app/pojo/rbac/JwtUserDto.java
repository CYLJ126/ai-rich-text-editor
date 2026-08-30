package com.arte.app.pojo.rbac;

import cn.hutool.core.collection.CollUtil;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.UserOnlineInfo;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * spring security 需要
 *
 * @author zhangsc
 */
public record JwtUserDto(UserOnlineInfo userOnlineInfo) implements UserDetails {

    @Serial
    private static final long serialVersionUID = 5614180934764038494L;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<SimpleGrantedAuthority> list = new ArrayList<>();
        if (CollUtil.isNotEmpty(userOnlineInfo.getMenuOperations())) {
            for (String s : userOnlineInfo.getMenuOperations()) {
                SimpleGrantedAuthority simpleGrantedAuthority = new SimpleGrantedAuthority(s);
                list.add(simpleGrantedAuthority);
            }
        }
        return list;
    }

    @Override
    public String getPassword() {
        return userOnlineInfo.getPassword();
    }

    @Override
    public String getUsername() {
        return userOnlineInfo.getUserName();
    }

    @Override
    public boolean isEnabled() {
        return StatusEnum.isNormal(userOnlineInfo.getStatus());
    }

}
