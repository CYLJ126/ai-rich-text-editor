package com.arte.app.platform.identity;

import com.arte.app.mapper.rbac.UserMapper;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.core.enums.StatusEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账号权威读取：复用 UserMapper，使用 app 物理库，不经过用户缓存、创建人过滤或线程路由。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
@Component
public class RbacAccountReader {

    private final DataSource dataSource;
    private final UserMapper users;

    public RbacAccountReader(@Qualifier("appDataSource") DataSource dataSource, @Qualifier("identityUserMapper") UserMapper users) {
        this.dataSource = dataSource;
        this.users = users;
    }

    public record Account(long id, String name, StatusEnum status, LocalDateTime createdAt) {
        public boolean active() {
            return StatusEnum.isNormal(status);
        }
    }

    public Optional<Account> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return account(users.selectIdentityByName(name));
    }

    public Optional<Account> findById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        return account(users.selectIdentityById(id));
    }

    /**
     * 锁定读，参与调用方绑定到同一物理 DataSource 的事务，不能降级为普通快照读。
     */
    public Optional<Account> findCurrentById(long id) {
        requireTransaction();
        return account(users.selectCurrentIdentityById(id));
    }

    public void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Identity requires a transaction on appDataSource");
        }
    }

    private Optional<Account> account(List<UserDto> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Ambiguous RBAC account");
        return rows.stream().findFirst().map(user -> new Account(user.getId(), user.getUserName(),
                user.getStatus(), user.getCreateTime()));
    }
}
