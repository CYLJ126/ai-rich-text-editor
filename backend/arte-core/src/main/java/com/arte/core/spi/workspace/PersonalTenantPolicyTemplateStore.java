package com.arte.core.spi.workspace;

import com.arte.core.pojo.workspace.PersonalTenantPolicyTemplate;

import java.util.Optional;

/**
 * 数据库模板权威
 * <p>
 * 在初始化原事务内当前读并锁定发布指针，缺失时不回退代码／配置默认值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public interface PersonalTenantPolicyTemplateStore {
    Optional<PersonalTenantPolicyTemplate> findCurrent();
}
