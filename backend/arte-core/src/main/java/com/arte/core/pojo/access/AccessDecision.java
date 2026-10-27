package com.arte.core.pojo.access;

import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 单项授权结论
 * <p>
 * 公开响应隐藏内部链路。拒绝可不返回版本，允许快照不是永久许可。
 * 表示一次单动作授权检查的结果，包含是否允许、原因、授权依据版本及检查时间。
 * 它是检查时的快照，不能作为永久访问许可。
 *
 * @param allowed   本次单动作授权检查是否允许，必须与 reason 是否为 ALLOWED 保持一致。
 * @param reason    本次判断的允许或拒绝原因，不能为空，例如成员资格缺失、政策禁止或授权权威不可用；公开响应须按接口规则隐藏内部细节。
 * @param versions  本次判断所依据的租户、空间、成员关系、ACL 及资源版本快照；允许时必填，拒绝时可为空，不适用的版本项可为空。
 * @param checkedAt 本次授权检查的时间，不能为空；结论仅反映检查时的状态，关键写入、派发及读取仍须按规则重新校验当前授权。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AccessDecision(boolean allowed, AccessReasonEnum reason,
                             AuthorizationVersions versions, Instant checkedAt) {

    public AccessDecision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(checkedAt, "checkedAt");
        PlatformChecks.require(allowed == (reason == AccessReasonEnum.ALLOWED), "allow flag and reason mismatch");
        PlatformChecks.require(!allowed || versions != null, "allowed decision requires version basis");
    }
}
