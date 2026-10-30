package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.CleanupAuthorizationStateEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 后台执行特定收尾工作的受限授权
 * <p>
 * 后台受限授权，不含新生成／未知调用重放权。目标及动作由受信治理服务固定，Worker 不能自行扩展。
 * 用于空间关闭、撤权等治理流程中的必要取消、状态核对、费用结算和耐久收尾，绑定指定服务主体、工作项及目标。
 * 授权须在受信存储登记，并在实际领域收尾事务中校验当前身份、工作项、范围、动作、目标、版本及有效期；原数据归属保持不变。
 * <p>
 * 它限定了谁可以执行、在哪个空间、处理哪个工作项和目标、允许哪些动作，以及有效期和版本。Worker 必须按登记的授权执行，不能自行扩大权限；
 * 原任务和账本的数据归属也不会转给系统主体。例如，关闭空间时，可授权指定服务主体取消某几个已登记任务，但不允许借此发起新的模型调用。
 *
 * @param id                    受限收尾授权的唯一标识，供 Worker 引用并从受信存储读取授权记录。
 * @param actorSubjectId        获准执行收尾的稳定主体标识；核验时须对应当前有效的 SERVICE 主体。
 * @param scope                 授权覆盖的完整租户与空间范围，不能为空；收尾请求和目标工作项须与该范围一致。
 * @param governanceOperationId 发行本授权的关闭、撤权等治理操作标识，用于将收尾资格关联到原治理操作。
 * @param domain                获准处理的业务领域标识，须符合平台 token 格式，并与登记工作项的领域一致。
 * @param actions               允许执行的固定动作集合，不能为空或空集合，构造时复制为不可变集合；仅覆盖必要收尾动作，Worker 不得自行扩展。
 * @param workKey               授权绑定的工作项键，与治理操作标识及领域共同定位登记的收尾工作项。
 * @param targetRefs            获准处理的固定目标引用集合，不能为空或空集合，构造时复制为不可变集合；具体目标及原数据关联由所属领域核实。
 * @param expiresAt             授权到期时间，不能为空；校验时必须严格早于该时点，过期续发由受信治理服务处理。
 * @param state                 授权当前状态，不能为空；ACTIVE 表示尚未撤销，仍须校验有效期及其他约束，REVOKED 表示已撤销。
 * @param version               非负的授权记录版本，用于授权更新的并发校验及收尾请求的预期版本核对。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CleanupAuthorization(
        String id,
        String actorSubjectId,
        Scope scope,
        String governanceOperationId,
        String domain,
        Set<Action> actions,
        String workKey,
        Set<String> targetRefs,
        Instant expiresAt,
        CleanupAuthorizationStateEnum state,
        long version) {

    public CleanupAuthorization {
        PlatformChecks.id(id, "id");
        PlatformChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.id(governanceOperationId, "governanceOperationId");
        PlatformChecks.token(domain, "domain");
        actions = Set.copyOf(actions);
        PlatformChecks.id(workKey, "workKey");
        targetRefs = Set.copyOf(targetRefs);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(version, "version");
        PlatformChecks.require(!actions.isEmpty() && !targetRefs.isEmpty(), "cleanup requires fixed actions and targets");
        targetRefs.forEach(v -> PlatformChecks.id(v, "targetRef"));
    }
}
