package com.arte.core.pojo.workspace;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.CleanupAuthorizationStateEnum;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次收尾授权检查的内部验证快照
 * <p>
 * 实际账本事务仍重检授权、工作项、有效期和数据关联。
 * 用于向领域收尾处理传递本次已核验的受限授权记录、执行服务主体及检查时间，由可信授权校验器在实际收尾事务中生成。
 * 构造器仅校验主体绑定、类型、状态及授权有效期；工作项、范围、动作、目标和版本仍须由授权校验器及领域处理核实。
 *
 * @param authorization 本次核验的受限收尾授权，不能为空，状态须为 ACTIVE，且在 checkedAt 时尚未到期；固定动作和目标约束仍适用于后续处理。
 * @param actor         本次核验的执行主体，不能为空，须为 ACTIVE 的 SERVICE 主体，且稳定主体标识与授权的 actorSubjectId 一致；原任务及账本归属保持不变。
 * @param checkedAt     本次授权检查的时间，不能为空且必须严格早于授权的 expiresAt；仅表示快照核验时点，不延长授权有效期。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record VerifiedCleanupAuthorization(CleanupAuthorization authorization, Principal actor, Instant checkedAt) {

    public VerifiedCleanupAuthorization {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(checkedAt, "checkedAt");
        PlatformChecks.require(actor.kind() == PrincipalKindEnum.SERVICE && actor.status() == PrincipalStatusEnum.ACTIVE
                && actor.subjectId().equals(authorization.actorSubjectId()), "cleanup requires active bound service actor");
        PlatformChecks.require(authorization.state() == CleanupAuthorizationStateEnum.ACTIVE && checkedAt.isBefore(authorization.expiresAt()), "cleanup authorization expired or revoked");
    }
}
