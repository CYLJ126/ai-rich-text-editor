package com.arte.core.spi.access;

import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceDescriptor;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.VerifiedScope;

/**
 * 领域／资料外发条件的受信扩展点，仅在公共基础判断通过后调用。
 * <p>
 * 该端口只能增加必要条件，返回 ALLOWED 不覆盖公共禁止或缺少的动作授权；不得再实现角色允许算法。
 * 不在判断过程中发起模型请求或外发资料。权威状态无法确认时拒绝，不能用异常降级为允许。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface DomainAccessConditionProvider {

    /**
     * 判断是否支持指定资源类型与动作的领域条件检查。
     */
    boolean supports(ResourceType resourceType, Action action);

    /**
     * 检查领域附加条件，返回允许或拒绝原因，不能覆盖公共权限拒绝。
     */
    AccessReasonEnum evaluate(TrustedActor actor, AccessRequest request,
                              ResourceDescriptor descriptor, VerifiedScope scope);
}
