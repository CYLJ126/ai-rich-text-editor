package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolAvailabilityService;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.po.ToolBindingPo;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 不改写用户绑定，通过发布后的兼容链选择可执行版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/01 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolBindingVersionSelector {
    private final ToolVersionMapper versionMapper;
    private final ToolVersionCompatibilityService compatibility;
    private final ToolAvailabilityService availability;
    private final ToolRegistry registry;

    public Optional<ToolVersionPo> select(ToolBindingPo binding, ToolPo tool) {
        List<ToolVersionPo> versions = versionMapper.selectVersions(tool.getToolId());
        return versions.stream().filter(version -> permitted(binding, version, versions))
                .filter(version -> executable(tool, version))
                .max(Comparator.comparing(ToolVersionPo::getPublishedAt,
                                Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                        .thenComparing(ToolVersionPo::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    public Optional<ToolVersionPo> selectCompatible(ToolBindingPo binding, ToolPo tool, ToolReference baseline) {
        if (!tool.getNamespace().equals(baseline.namespace()) || !tool.getName().equals(baseline.name()))
            return Optional.empty();
        List<ToolVersionPo> versions = versionMapper.selectVersions(tool.getToolId());
        return select(binding, tool).filter(selected -> compatibility.follows(baseline.version(), selected, versions));
    }

    public Optional<ToolVersionPo> selectRequested(ToolBindingPo binding, ToolPo tool, ToolReference requested) {
        if (!tool.getNamespace().equals(requested.namespace()) || !tool.getName().equals(requested.name())) {
            return Optional.empty();
        }
        List<ToolVersionPo> versions = versionMapper.selectVersions(tool.getToolId());
        return versions.stream().filter(version -> version.getVersion().equals(requested.version()))
                .filter(version -> permitted(binding, version, versions))
                .filter(version -> executable(tool, version)).findFirst();
    }

    public String policy(ToolBindingPo binding) {
        return binding.getVersionPolicy() == null ? "follow-compatible" : binding.getVersionPolicy();
    }

    private boolean permitted(ToolBindingPo binding, ToolVersionPo version, List<ToolVersionPo> versions) {
        return "pinned".equals(policy(binding)) ? binding.getToolVersion().equals(version.getVersion())
                : compatibility.follows(binding.getToolVersion(), version, versions);
    }

    public boolean executable(ToolPo tool, ToolVersionPo version) {
        ToolReference reference = new ToolReference(tool.getNamespace(), tool.getName(), version.getVersion());
        return version.getLifecycleState() == ToolLifecycleStateEnum.PUBLISHED
                && availability.isAvailable(reference) && registry.resolve(reference).isPresent();
    }
}
