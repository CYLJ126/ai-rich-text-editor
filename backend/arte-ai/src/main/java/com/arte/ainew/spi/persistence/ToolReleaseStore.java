package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolPublicationValidation;
import com.arte.ainew.pojo.tool.ToolRelease;
import com.arte.ainew.pojo.tool.ToolReleaseCommand;
import com.arte.ainew.pojo.tool.ToolReleaseReceipt;
import com.arte.core.pojo.platform.TrustedActor;
import reactor.core.publisher.Mono;

/**
 * 平台受管 TOOL 生命周期的底层事务端口，只供已授权的内部管理流程调用。
 * 状态、命令防重和不可变审计回执同库提交；数据库故障通过 onError 传播。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public interface ToolReleaseStore {

    /**
     * 读取当前状态，不缓存撤销；存在定义但尚无治理行时派生 version=0 的 DRAFT。
     *
     * @param tool type=tool 的固定引用。
     * @return 当前状态；定义不存在时为空 Mono。
     */
    Mono<ToolRelease> find(DefinitionRef tool);

    /**
     * 读取该工具的管理审计回执；游标表示存储位置，不表达版本新旧或跨页一致快照。
     *
     * @param tool     固定工具版本，外层须复核当前审计读取资格。
     * @param afterKey 上页返回的不透明游标，首页为 null。
     * @param limit    最多返回条数，范围为 1～100。
     * @return 不可变原始决定及后续游标。
     */
    Mono<ToolCatalogStore.Page<ToolReleaseReceipt>> history(DefinitionRef tool, String afterKey, int limit);

    /**
     * 乐观版本检查及领域状态变更，与首次回执／审计原子提交。
     *
     * @param actor      受信实际操作者，外层仍须复核当前管理资格，不能从请求体绑定。
     * @param command    固定版本、动作、expectedVersion、幂等键及原因。
     * @param validation 服务端产生的发布／重启用预检，其他动作必须为 null；不是客户端凭据。
     * @return 首次记录为 APPLIED、同语义重放为 REPLAYED；实际领域决定读取 receipt.outcome；变更键语义返回 IDEMPOTENCY_CONFLICT。
     */
    Mono<StoreOutcome<ToolReleaseReceipt>> execute(TrustedActor actor, ToolReleaseCommand command, ToolPublicationValidation validation);
}
