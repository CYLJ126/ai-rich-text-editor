package com.arte.app.platform.persistence;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * 平台专用会话的容器。工厂和模板不单独注册为全局 Bean，避免影响旧 MyBatis 自动装配。
 * Mapper 的连接通过 SpringManagedTransactionFactory 加入物理 app 库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
final class PlatformMybatisSession {
    private final SqlSessionFactory factory;
    private final SqlSessionTemplate template;

    PlatformMybatisSession(SqlSessionFactory factory) {
        this.factory = factory;
        this.template = new SqlSessionTemplate(factory);
    }

    <T> T mapper(Class<T> type) {
        return template.getMapper(type);
    }

    SqlSessionFactory factory() {
        return factory;
    }
}
