package com.arte.core.spi.access;

import com.arte.core.pojo.access.ResourceDescriptor;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.access.ResourceType;

import java.util.Optional;

/**
 * 由资源领域实现，同事务读取原始归属／状态／版本；不得调用已受保护的读取 API 造成递归，不返回正文。
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
}
