package com.arte.base.model.artifact;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

/**
 * 通用产物引用及完整性元数据；字节存储与业务附件关系分开。
 *
 * contentDigest 使用 sha256: 加小写十六进制摘要；持有引用不代表已获得读取权限。
 */
public record ArtifactRef(
        String artifactId,
        ResourceRef owner,
        String mediaType,
        long sizeBytes,
        String contentDigest
) {
    public ArtifactRef {
        artifactId = ContractChecks.identifier(artifactId, "artifactId");
        owner = ContractChecks.required(owner, "owner");
        mediaType = ContractChecks.identifier(mediaType, "mediaType");
        if (!mediaType.matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+"))
            throw new IllegalArgumentException("invalid mediaType");
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
        if (contentDigest == null || !contentDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid SHA-256 contentDigest");
    }
}
