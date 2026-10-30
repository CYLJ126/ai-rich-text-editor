package com.arte.core.spi.access;

import com.arte.core.pojo.access.ResourceDescriptor;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.access.ResourceType;

import java.util.Optional;

/**
 * 由资源领域实现，同事务读取原始归属／状态／版本；不得调用已受保护的读取 API 造成递归，不返回正文。
 * find 用于普通读取；findCurrent 用于最终写入或受理前，核验当前是否仍然有资格。 返回的数据类型可以相同，但一致性和并发保护要求不同。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ResourceDescriptorProvider {

    /**
     * 判断是否支持读取指定资源类型的可信描述。
     */
    boolean supports(ResourceType type);

    /**
     * 按资源引用读取原始归属、状态和版本，资源不存在时返回空。
     */
    Optional<ResourceDescriptor> find(ResourceRef resource);

    /**
     * 在调用方原写事务、父范围门控下读取当前领域事实，不能使用旧快照。
     */
    Optional<ResourceDescriptor> findCurrent(ResourceRef resource);
}
