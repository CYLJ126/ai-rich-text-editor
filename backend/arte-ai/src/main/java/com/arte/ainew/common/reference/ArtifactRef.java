package com.arte.ainew.common.reference;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;

/**
 * 已转存、可授权读取的本系统产物引用；供应商临时 URL 不能冒充此引用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ArtifactRef(String artifactId, String version, String mediaType,
                          String contentDigest, long byteSize) implements Serializable {
    public ArtifactRef {
        ContractChecks.id(artifactId, "artifactId");
        new DefinitionRef("artifact", artifactId, version);
        ContractChecks.text(mediaType, "mediaType", 128);
        ContractChecks.require(mediaType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+"), "mediaType must be a MIME type");
        ContractChecks.digest(contentDigest, "contentDigest");
        ContractChecks.range(byteSize, "byteSize", 0, Long.MAX_VALUE);
    }
}
