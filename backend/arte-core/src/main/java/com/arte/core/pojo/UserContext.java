package com.arte.core.pojo;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;

/**
 * 用户信息上下文
 *
 * @author zhangsc
 * @since 2025/1/2 16:35
 */
public class UserContext {

    private static final ThreadLocal<UserOnlineInfo> USER_INFO = new ThreadLocal<>();
    public static final UserOnlineInfo DEFAULT_USER = new UserOnlineInfo().setUserName("system");

    private UserContext() {
    }

    public static void setUserOnlineInfo(UserOnlineInfo userOnlineInfo) {
        USER_INFO.set(userOnlineInfo);
    }

    public static UserOnlineInfo getUserOnlineInfo() {
        UserOnlineInfo userInfo = USER_INFO.get();
        Assert.notNull(userInfo, MessageUtils.get("error.field.loginUserUnavailable"));
        return userInfo;
    }

    public static boolean hasUserOnlineInfo() {
        UserOnlineInfo userOnlineInfo = USER_INFO.get();
        return userOnlineInfo != null;
    }

    public static String getUserName() {
        if (hasUserOnlineInfo()) {
            return getUserOnlineInfo().getUserName();
        }
        return StrUtil.EMPTY;
    }

    public static void clear() {
        USER_INFO.remove();
    }

    public static void setDefaultUser() {
        setUserOnlineInfo(DEFAULT_USER);
    }

}
