package com.arte.ainew.common.reference;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.ResourceVersionRef;

import java.io.Serializable;
import java.util.Objects;

/**
 * 来源定位键
 * <p>
 * 本次输入中的来源标识及实际摘录摘要，来源展示与再次使用仍需重新授权。
 * 用于关联 AI 输入或结果中引用的来源，并核对实际使用的摘录内容。
 *
 * @param citationId    本次输入或结果中的来源引用标识，用于关联上下文片段与结果引用；在所属快照或结果的来源列表内须唯一，不是资源的全局标识。
 * @param resource      来源资源的固定版本或草稿引用，不能为空，包含资源类型、标识及可选的范围等定位信息；引用不携带正文或授予读取、外发权限。
 * @param excerptDigest 本次实际使用的摘录内容的 SHA-256 摘要，必须为 64 位小写十六进制字符串，用于核对实际引用内容；不是自然语言摘要，构造器仅校验格式，内容一致性须由可信边界核验。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record SourceRef(String citationId, ResourceVersionRef resource, String excerptDigest) implements Serializable {

    public SourceRef {
        ContractChecks.id(citationId, "citationId");
        Objects.requireNonNull(resource, "resource");
        ContractChecks.digest(excerptDigest, "excerptDigest");
    }
}
