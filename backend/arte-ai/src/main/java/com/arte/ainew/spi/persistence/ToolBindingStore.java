package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.core.pojo.platform.TrustedActor;
import reactor.core.publisher.Mono;

/**
 * 私有 TOOL 绑定底层事务端口；owner 必须来自可信入口，外层仍须复核当前管理／使用资格。
 * 不替代通用 BindingManager，不参与 GENERATION 控制面。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public interface ToolBindingStore {

    /**
     * 按真实 owner 读取当前绑定，越界或不存在均返回空。
     */
    Mono<ToolBindingDefinition> find(ExecutionOwner owner, String bindingId);

    /**
     * 读取该 owner 的不可变修订；ref 必须为 binding/rN，越界返回空。
     */
    Mono<ToolBindingRevision> revision(ExecutionOwner owner, DefinitionRef ref);

    /**
     * owner 范围分页；afterKey 为不透明存储游标，limit 为 1～100，不承诺跨页一致快照。
     */
    Mono<ToolCatalogStore.Page<ToolBindingDefinition>> list(ExecutionOwner owner, String afterKey, int limit);

    /**
     * 原子保存当前指针、不可变修订、幂等回执及审计；拒绝决定也耐久保存。
     */
    Mono<StoreOutcome<ToolBindingReceipt>> execute(TrustedActor actor, ExecutionOwner owner, ToolBindingCommand command);

    /**
     * 对外管理专用：当前授权与指针／修订／回执使用同一事务和线程；不允许默认降级。
     */
    default Mono<StoreOutcome<ToolBindingReceipt>> executeGuarded(TrustedActor actor, ExecutionOwner owner, ToolBindingCommand command,
                                                                  Runnable authorization) {
        return Mono.error(new UnsupportedOperationException("Transactional tool authorization is required"));
    }

    /**
     * 无写入的升级预览；settings 是完整目标配置，expectedVersion 防止预览过期。
     */
    Mono<ToolBindingUpgradePreview> preview(ExecutionOwner owner, String bindingId, long expectedVersion, ToolBindingSettings settings);

    /**
     * 新受理解析并保存固定结果；follow-compatible 仅在此选版本，不试调用工具。
     */
    Mono<ToolBindingResult<ToolBindingResolution>> resolve(ExecutionOwner owner, String bindingId);

    /**
     * 当前调用方案只读预览；不保存解析材料或锁行，不调用执行器。
     */
    default Mono<ToolBindingResult<ToolBindingResolution>> inspect(ExecutionOwner owner, String bindingId) {
        return Mono.error(new UnsupportedOperationException("Read-only tool inspection is required"));
    }


    /**
     * 读取已保存的固定结果并复核当前启用／生命周期／服务器上限，不重新选择版本。
     */
    Mono<ToolBindingResult<ToolBindingResolution>> fixed(ExecutionOwner owner, DefinitionRef ref);

    /**
     * owner 范围的原始管理回执；afterKey 为不透明游标，limit 为 1～100。
     */
    Mono<ToolCatalogStore.Page<ToolBindingReceipt>> history(ExecutionOwner owner, String bindingId, String afterKey, int limit);
}
