package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolAvailabilityService;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolProviderMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 基于数据库目录状态的工具可用性判断服务
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Service
public class DatabaseToolAvailabilityService implements ToolAvailabilityService {

    public static final String PROVIDER_STATUS_ENABLED = "enabled";

    @Resource
    private ToolMapper toolMapper;
    @Resource
    private ToolVersionMapper toolVersionMapper;
    @Resource
    private ToolProviderMapper toolProviderMapper;

    @Override
    public boolean isAvailable(ToolReference reference) {
        if (reference == null) {
            return false;
        }
        ToolPo tool = toolMapper.selectByIdentity(reference.namespace(), reference.name()).orElse(null);
        if (tool == null || tool.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
            return false;
        }
        ToolVersionPo version = toolVersionMapper.selectExact(tool.getToolId(), reference.version()).orElse(null);
        if (version == null || version.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
            return false;
        }
        ToolProviderPo provider = toolProviderMapper.selectByProviderId(tool.getProviderId()).orElse(null);
        return provider != null && PROVIDER_STATUS_ENABLED.equalsIgnoreCase(provider.getStatus());
    }
}
