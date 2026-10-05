package com.arte.core.interceptor;

import org.apache.ibatis.mapping.SqlCommandType;

/**
 * 旧业务 INSERT 插件；公共参数绑定与审计逻辑见 {@link MybatisInterceptor}。
 *
 * @author zhangsc
 * @since 2025/12/20 23:26
 */
public final class MybatisInsertInterceptor extends MybatisInterceptor {
    public MybatisInsertInterceptor() {
        super(SqlCommandType.INSERT);
    }
}
