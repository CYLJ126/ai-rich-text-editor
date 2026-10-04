package com.arte.ainew.pojo.media;

import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;

import java.util.List;
import java.util.Objects;

/**
 * 媒体生成请求参数
 * <p>
 * 媒体生成最小输入，不要求当前启用媒体执行；新增专有参数应类型化并受能力／Schema 校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record MediaRequest(MediaKind mediaKind, String prompt,
                           List<ArtifactRef> references) implements CapabilityInput {
    public enum MediaKind {IMAGE, AUDIO, VIDEO}

    public MediaRequest {
        Objects.requireNonNull(mediaKind, "mediaKind");
        ContractChecks.text(prompt, "prompt", ContractChecks.MAX_TEXT_CHARS);
        references = ContractChecks.list(references, "references", 0, ContractChecks.MAX_PARTS);
    }

    @Override
    public CapabilityDescriptor.Kind kind() {
        return CapabilityDescriptor.Kind.MEDIA;
    }
}
