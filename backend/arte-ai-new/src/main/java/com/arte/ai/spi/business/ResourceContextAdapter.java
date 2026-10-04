package com.arte.ai.spi.business;

import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.SourceRef;

/** 组合模块提供已授权的固定资料；AI 核心不直接访问业务数据库。 */
public interface ResourceContextAdapter {
    String resourceType();
    ContextFragment resolve(ExecutionContext viewer, ResourceContextSelection selection, String citationId);
    /** 显示时检查读取／AI 权限，外发时额外检查外发权限及草稿编辑许可。 */
    void authorize(ExecutionContext viewer, SourceRef source, boolean forEgress);
}
