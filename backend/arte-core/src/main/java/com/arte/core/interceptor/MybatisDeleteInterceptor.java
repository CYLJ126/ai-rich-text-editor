package com.arte.core.interceptor;

import org.apache.ibatis.mapping.SqlCommandType;

/**
 * 旧业务 DELETE 插件；公共参数绑定与审计逻辑见 {@link MybatisInterceptor}。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public final class MybatisDeleteInterceptor extends MybatisInterceptor {
    public MybatisDeleteInterceptor() {
        super(SqlCommandType.DELETE);
    }
}
