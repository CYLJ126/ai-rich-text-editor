package com.arte.ai.pojo.tool.po;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 助手工具运行时解析的一次联表查询投影。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class AssistantToolResolutionPo {

    private String bindingId;
    private Integer sortOrder;
    private Map<String, Object> assistantPolicyOverride;
    private String workspaceId;
    private String toolId;
    private String toolVersion;
    private String providerId;
    private String namespace;
    private String name;
    private String bindingCredentialReference;
    private String providerCredentialReference;
    private Map<String, Object> bindingConfiguration;
    private Map<String, Object> bindingPolicyOverride;
    private Map<String, Object> defaultConfiguration;
    private Map<String, Object> defaultPolicy;
}
