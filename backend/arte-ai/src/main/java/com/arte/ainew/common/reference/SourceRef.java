package com.arte.ainew.common.reference;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 本次输入中的来源标识及实际摘录摘要；来源展示与再次使用仍需重新授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record SourceRef(String citationId, ResourceRef resource, String excerptDigest) implements Serializable {
    public SourceRef {
        ContractChecks.id(citationId, "citationId");
        Objects.requireNonNull(resource, "resource");
        ContractChecks.digest(excerptDigest, "excerptDigest");
    }
}
