package com.arte.ai.service.tool;

import com.arte.ai.mapper.tool.ToolBindingMapper;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolUpgradePreview;
import com.arte.ai.pojo.tool.po.ToolBindingPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具兼容升级的发布预览服务。
 * <p>
 * 校验兼容基准与目标版本的发布关系，检查契约及风险权限的兼容性，
 * 并统计基准兼容链中跟随升级的绑定数和该工具的锁定绑定数，供管理员评估发布影响。
 * 预览仅查询数据，不发布版本或修改用户绑定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/1 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolUpgradePreviewService {
    private final ToolMapper toolMapper;
    private final ToolVersionMapper versionMapper;
    private final ToolBindingMapper bindingMapper;
    private final ToolVersionCompatibilityService compatibility;

    public ToolUpgradePreview preview(ToolReference reference, String baseVersion) {
        var tool = toolMapper.selectByIdentity(reference.namespace(), reference.name()).orElseThrow();
        List<ToolVersionPo> versions = versionMapper.selectVersions(tool.getToolId());
        ToolVersionPo next = versions.stream().filter(v -> v.getVersion().equals(reference.version())).findFirst().orElseThrow();
        ToolVersionPo base = versions.stream().filter(v -> v.getVersion().equals(baseVersion)).findFirst().orElseThrow();
        compatibility.requireReleaseBaseline(base, next);
        List<String> problems = compatibility.problems(base, next);
        List<String> baselines = versions.stream().map(ToolVersionPo::getVersion)
                .filter(version -> compatibility.follows(version, base, versions)).toList();
        long following = bindingMapper.selectCount(new QueryWrapper<ToolBindingPo>()
                .eq("tool_id", tool.getToolId()).in("tool_version", baselines)
                .and(q -> q.eq("version_policy", "follow-compatible").or().isNull("version_policy")));
        long pinned = bindingMapper.selectCount(new QueryWrapper<ToolBindingPo>()
                .eq("tool_id", tool.getToolId()).eq("version_policy", "pinned"));
        return new ToolUpgradePreview(problems.isEmpty(), problems, following, pinned);
    }
}
