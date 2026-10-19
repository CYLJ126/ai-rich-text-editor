package com.arte.app.platform.identity;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在 app 物理库的当前事务读取既有 RBAC 关系，不使用登录缓存或 ThreadLocal 路由。
 * 关系与角色使用锁定读，允许依据持续到本次工具变更提交；不创建新权限权威。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class RbacToolPermissionReader {
    private final DataSource source;
    private final Permissions mapper;

    public RbacToolPermissionReader(DataSource source) {
        this.source = Objects.requireNonNull(source);
        var config = new Configuration(new Environment("tool-rbac", new SpringManagedTransactionFactory(), source));
        config.setCacheEnabled(false);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setDefaultStatementTimeout(30);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        config.addMapper(Permissions.class);
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(Permissions.class);
    }

    /**
     * 与既有 pcs 一致支持 admin 操作标记；空间资格仍单独检查，角色名称不自动等于权限。
     */
    public Set<String> current(String accountName) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Current RBAC reads require appDataSource transaction");
        return RbacAuthorizationReads.read(source, this, accountName, () -> loadCurrent(accountName));
    }

    private Set<String> loadCurrent(String accountName) {
        var direct = mapper.relations(accountName, "user_to_operation");
        var roles = mapper.relations(accountName, "user_to_role");
        if (direct.size() > 256 || roles.size() > 256)
            throw new AccessDeniedException("RBAC relation capacity exceeded");
        var result = new HashSet<>(direct);
        if (!roles.isEmpty()) {
            var active = mapper.roles(roles).stream().filter(r -> "1".equals(r.status())).map(RoleState::code).sorted().toList();
            if (!active.isEmpty()) {
                var inherited = mapper.roleOperations(active);
                if (inherited.size() > 1024) throw new AccessDeniedException("RBAC operation capacity exceeded");
                result.addAll(inherited);
            }
        }
        return Set.copyOf(result);
    }

    public record RoleState(String code, String status) {
    }

    /**
     * 只注册到上面的隔离工厂，SQL 使用既有索引 (source,binding_type,target)。
     */
    public interface Permissions {
        @Select("SELECT target FROM arte_rbac_relation WHERE source=#{name} AND binding_type=#{kind} ORDER BY target LIMIT 257 FOR UPDATE")
        List<String> relations(@Param("name") String name, @Param("kind") String kind);

        @Select({"<script>SELECT role_code AS code,status FROM arte_rbac_role WHERE role_code IN",
                "<foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach>",
                "ORDER BY role_code FOR UPDATE</script>"})
        List<RoleState> roles(@Param("codes") List<String> codes);

        @Select({"<script>SELECT target FROM arte_rbac_relation WHERE binding_type='role_to_operation' AND source IN",
                "<foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach>",
                "ORDER BY source,target LIMIT 1025 FOR UPDATE</script>"})
        List<String> roleOperations(@Param("codes") List<String> codes);
    }
}
