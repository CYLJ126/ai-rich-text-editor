package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;

import java.util.Objects;

/**
 * 已校验目录内容的模型候选
 * <p>
 * 不是授权结果，不携带 Grant、预算账户或凭据值。
 *
 * @param binding    当前受信目录中的精确模型绑定及能力契约
 * @param connection 与绑定对应的连接快照，仅包含凭据引用
 * @param rate       与绑定对应的模型费率定义，不包含预算余额
 * @param releaseRef 本次目录的发布标识，不以此推断模型管理身份
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public record ModelAccessCandidate(ResolvedBinding binding, ConnectionDefinition connection, RateDefinition rate,
                                   String releaseRef) {

    public ModelAccessCandidate {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(connection);
        Objects.requireNonNull(rate);
        Objects.requireNonNull(releaseRef);
    }

    public ModelPublication publication(ModelPublication origin) {
        return new ModelPublication(origin.source(), origin.catalogRevision(), releaseRef, binding, connection, rate, origin.planRef(), origin.credentialKind());
    }
}
