package com.arte.core.pojo.platform;

import com.arte.core.utils.validation.ContractChecks;

import java.io.Serializable;

/**
 * 长期对象的执行归属键
 * <p>
 * 只保存租户、空间和稳定主体 ID，不复制名称、当前状态或会过期的授权快照。
 * 由可信边界解析，不能相信外部请求体自报值；与资源治理归属、访问资格分开。
 * 保留既有契约标识校验，不在迁移中收紧历史输入。
 *
 * @param tenantId    租户 ID，必填
 * @param workspaceId 工作空间 ID，必填
 * @param subjectId   主体 ID，必填
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionOwner(String tenantId, String workspaceId, String subjectId) implements Serializable {

    public ExecutionOwner {
        ContractChecks.id(tenantId, "tenantId");
        ContractChecks.id(workspaceId, "workspaceId");
        ContractChecks.id(subjectId, "subjectId");
    }

}
