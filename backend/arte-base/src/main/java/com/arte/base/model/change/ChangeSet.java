package com.arte.base.model.change;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.schema.SchemaRef;

import java.util.List;
import java.util.Set;

/**
 * 跨领域变更信封；P 为目标领域的具体补丁，Schema 与规则由该领域提供。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ChangeSet<P>(
        String changeSetId,
        String targetDomain,
        ResourceRef target,
        String baseVersion,
        String draftDigest,
        SchemaRef patchSchema,
        P patch,
        List<SourceRef> sources,
        Set<String> applicationConstraints,
        ChangeStatus status
) {
}
