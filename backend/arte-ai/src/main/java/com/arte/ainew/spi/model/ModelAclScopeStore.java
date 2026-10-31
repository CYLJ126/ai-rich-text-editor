package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.core.pojo.platform.Scope;

import java.util.Optional;

/**
 * 迁移范围权威
 * <p>
 * 无删除或退回 LEGACY 的写接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public interface ModelAclScopeStore {

    Optional<ModelAclScope> find(Scope scope);

    Optional<ModelAclScope> findCurrent(Scope scope);

    boolean insert(ModelAclScope value);

    boolean replace(ModelAclScope value, long expectedVersion);
}
