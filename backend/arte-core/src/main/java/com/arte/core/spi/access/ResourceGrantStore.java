package com.arte.core.spi.access;

import com.arte.core.pojo.access.ResourceAccess;
import com.arte.core.pojo.access.ResourceGrant;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;

import java.util.Optional;

/**
 * 公共 ACL 权威存储
 * <p>
 * 范围精确匹配，初始 ACL 与正文同事务，允许来源独立、撤销历史保留。所有 replace 为版本 CAS。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ResourceGrantStore {

    /**
     * 按资源引用查询 ACL 根记录，不存在时返回空。
     */
    Optional<ResourceAccess> findAccess(ResourceRef resource);

    /**
     * 在当前事务内锁定并读取资源的 ACL 根记录。
     */
    Optional<ResourceAccess> lockAccess(ResourceRef resource);

    /**
     * 新增资源的 ACL 根记录，唯一条件冲突时返回 false。
     */
    boolean insertAccess(ResourceAccess access);

    /**
     * 按预期 ACL 版本更新根记录，版本条件不满足时返回 false。
     */
    boolean replaceAccess(ResourceAccess access, long expectedAclVersion);

    /**
     * 有界扫描指定资源授权，服务层判定代次、期限、来源及当前允许资格。
     */
    PageResult<ResourceGrant> findGrants(ResourceRef resource, PageQuery query);

    /**
     * 在指定资源范围内查询授权记录，不存在时返回空。
     */
    Optional<ResourceGrant> findGrant(ResourceRef resource, String grantId);

    /**
     * 新增独立来源的授权记录，唯一条件冲突时返回 false。
     */
    boolean insertGrantIfAbsent(ResourceGrant grant);

    /**
     * 按预期版本更新授权记录，撤销保留历史，版本条件不满足时返回 false。
     */
    boolean replaceGrant(ResourceGrant grant, long expectedVersion);
}
