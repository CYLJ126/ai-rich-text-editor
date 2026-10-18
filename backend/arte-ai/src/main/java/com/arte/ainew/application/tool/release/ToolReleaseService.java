package com.arte.ainew.application.tool.release;

import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolRelease;
import com.arte.ainew.pojo.tool.ToolReleaseCommand;
import com.arte.ainew.pojo.tool.ToolReleaseReceipt;
import com.arte.ainew.spi.persistence.ToolReleaseStore;
import com.arte.core.pojo.platform.TrustedActor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Optional;

/**
 * 受信内部生命周期管理；公开入口通过 ToolManagementService 接入当前管理授权及事务门控。
 * 停用／废弃不依赖来源或执行器健康，避免部署故障时无法撤销；发布与重新启用必须预检。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
@Slf4j
public final class ToolReleaseService {
    private final ToolReleaseStore store;
    private final LocalToolPublicationValidator validator;

    public ToolReleaseService(ToolReleaseStore store, LocalToolPublicationValidator validator) {
        this.store = Objects.requireNonNull(store);
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * 受信调用方须先核实当前主体及管理范围；重放也不能跳过外层授权。
     *
     * @param actor   实际受信操作者，不是工具执行归属。
     * @param command 带 expectedVersion、幂等键和原因的固定版本管理命令。
     * @return 首次或重放回执；领域决定读取 receipt.outcome，预检失败也不改变当前状态。
     */
    public Mono<StoreOutcome<ToolReleaseReceipt>> execute(TrustedActor actor, ToolReleaseCommand command) {
        return execute(actor, command, null);
    }

    /**
     * 先做本地无副作用预检，再由底层在提交事务中执行最终授权；不跨元数据线程持有数据库锁。
     */
    public Mono<StoreOutcome<ToolReleaseReceipt>> execute(TrustedActor actor, ToolReleaseCommand command, Runnable authorization) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(command);
        return Mono.defer(() -> switch (command.action()) {
            case PUBLISH ->
                    validator.validate(command.tool(), command.review()).flatMap(v -> save(actor, command, v, authorization));
            case REENABLE ->
                    store.find(command.tool()).map(release -> Optional.ofNullable(release.publication()).map(ToolRelease.Publication::review))
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(review -> validator.validate(command.tool(), review.orElse(null)))
                            .flatMap(v -> save(actor, command, v, authorization));
            case DEPRECATE, DISABLE -> save(actor, command, null, authorization);
        }).doOnNext(outcome -> {
            if (outcome.code() == StoreOutcome.Code.APPLIED && outcome.value().outcome() == StoreOutcome.Code.APPLIED) {
                var receipt = outcome.value();
                log.info("AI_TOOL_RELEASE action={} tool={} version={} state={} commandId={}", command.action(), command.tool(),
                        receipt.release().version(), receipt.release().state(), receipt.commandId());
            }
        });
    }

    private Mono<StoreOutcome<ToolReleaseReceipt>> save(TrustedActor actor, ToolReleaseCommand command,
                                                        com.arte.ainew.pojo.tool.ToolPublicationValidation validation, Runnable authorization) {
        return authorization == null ? store.execute(actor, command, validation) : store.executeGuarded(actor, command, validation, authorization);
    }
}
