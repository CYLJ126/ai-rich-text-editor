package com.arte.base.model.artifact;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;

/**
 * 来源字节流由调用方关闭；maxBytes 是请求上限，提供者仍须执行自身上限。
 */
public record ArtifactUpload(ExecutionScope scope, ResourceRef owner, String mediaType, long maxBytes,
                             String expectedDigest, Instant expiresAt) {
    public ArtifactUpload {
        scope = ContractChecks.required(scope, "scope");
        owner = ContractChecks.required(owner, "owner");
        mediaType = ContractChecks.identifier(mediaType, "mediaType");
        if (!mediaType.matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+"))
            throw new IllegalArgumentException("invalid mediaType");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
        if (expectedDigest != null && !expectedDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid expectedDigest");
    }
}
