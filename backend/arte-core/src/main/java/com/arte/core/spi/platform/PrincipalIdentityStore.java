package com.arte.core.spi.platform;

import com.arte.core.pojo.platform.PrincipalIdentity;

import java.time.Instant;
import java.util.Optional;

/**
 * 受信身份适配使用的映射存储
 * <p>
 * subjectId 及 (provider,externalUserId) 均唯一且不可重绑。
 * insertIfAbsent 不覆盖已有映射，冲突由身份适配核对；retire 保留关联并使用版本条件，不删除历史键。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PrincipalIdentityStore {

    /**
     * 按稳定主体 ID 查询身份映射，不存在时返回空。
     */
    Optional<PrincipalIdentity> findBySubjectId(String subjectId);

    /**
     * 按身份提供方和外部用户 ID 查询身份映射，不存在时返回空。
     */
    Optional<PrincipalIdentity> findByExternalIdentity(String provider, String externalUserId);

    /**
     * 新增身份映射，唯一条件冲突时返回 false，不覆盖已有关联。
     */
    boolean insertIfAbsent(PrincipalIdentity identity);

    /**
     * 按预期版本注销身份映射并保留关联，版本条件不满足时返回 false。
     */
    boolean retire(String subjectId, Instant retiredAt, long expectedVersion);
}
