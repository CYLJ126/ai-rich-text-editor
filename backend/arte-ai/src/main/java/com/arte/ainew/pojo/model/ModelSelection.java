package com.arte.ainew.pojo.model;

import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 空间显式启用的实际资源精确版本，不以名称或裸审核绑定定位模型。
 *
 * @param resourceId 稳定实际模型资源 ID
 * @param binding    属于同一资源的固定发布绑定
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 14:20 ✾
 */
public record ModelSelection(String resourceId, DefinitionRef binding) {

    public ModelSelection {
        PlatformChecks.id(resourceId, "resourceId");
        Objects.requireNonNull(binding).requireType("binding");
        PlatformChecks.require(!"latest".equalsIgnoreCase(binding.version()), "Fixed selection required");
    }
}
