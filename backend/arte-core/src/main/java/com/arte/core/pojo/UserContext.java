package com.arte.core.pojo;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.arte.core.i18n.MessageUtils;

/**
 * 用户信息上下文
 *
 * @deprecated 新代码使用 com.arte.base.model.execution.ExecutionContext 显式传递主体与作用域。
 * 本类保留旧 ThreadLocal 行为供既有调用方使用，不转换为新上下文，也不自动迁移引用。
 * 新上下文的认证和租户／空间归属由服务端接入层验证。
 *
 * @author zhangsc
 * @since 2025/1/2 16:35
 */
@Deprecated
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
