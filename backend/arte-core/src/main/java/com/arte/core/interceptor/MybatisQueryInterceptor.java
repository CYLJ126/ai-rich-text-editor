package com.arte.core.interceptor;

import org.apache.ibatis.mapping.SqlCommandType;

/**
 * 旧业务 SELECT 插件；公共参数绑定与审计逻辑见 {@link MybatisInterceptor}。
 *
 * @author zhangsc
 * @since 2025/7/21 19:26
 */
public final class MybatisQueryInterceptor extends MybatisInterceptor {
    public MybatisQueryInterceptor() {
        super(SqlCommandType.SELECT);
    }
}
