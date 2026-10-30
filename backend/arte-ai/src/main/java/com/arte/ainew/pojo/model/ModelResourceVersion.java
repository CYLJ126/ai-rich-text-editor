package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 模型管理资源与精确发布版本的不可变关联。
 * <p>
 * 将稳定的模型管理身份与一次经核对的发布内容关联，同时保留关联操作者及时间。
 * 同一管理资源可关联同一绑定族的多个精确版本，新版本沿用原资源及公共 ACL，不覆盖已关联快照或改写历史执行。
 * 精确发布版本由 publication 中的 binding 引用定位，本对象不表示管理资源的乐观锁版本，也不直接授予模型使用资格。
 *
 * @param resource    所关联模型管理资源的稳定引用，包含租户、model 类型及资源 ID；不同发布版本可以关联同一资源，归属以 ModelResource 为准。
 * @param publication 经受信来源核对的精确发布快照，保存绑定、连接、费率及来源证据；关联后不按最新目录内容替换。
 * @param linkedBy    执行本次关联的稳定主体 ID，用于审计；可能与资源创建者不同，不表示凭据责任主体或调用执行身份。
 * @param linkedAt    首次成功关联的时间点，由服务端生成，当前服务按 UTC 微秒精度记录；命令重放或重复关联同一内容时保留原值。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public record ModelResourceVersion(ResourceRef resource, ModelPublication publication, String linkedBy,
                                   Instant linkedAt) {

    public ModelResourceVersion {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(publication);
        Objects.requireNonNull(linkedAt);
        PlatformChecks.require(resource.type().equals(com.arte.core.constant.platform.PlatformResourceTypes.MODEL), "Not a model resource");
        PlatformChecks.id(linkedBy, "linkedBy");
    }
}
