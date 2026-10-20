package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 系统收尾端口；每次领取／提交复核数据库租约、原状态及原操作，不能注册为用户命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public interface ToolOutcomeStore {

    Mono<Integer> recoverUnknownBatch();

    Mono<List<ToolOutcomeCheck.Claim>> claim(String workerId, int limit);

    Mono<Boolean> finish(ToolOutcomeCheck.Claim claim, ToolOutcomeEvidence evidence, String reason, boolean needsReview);

    Mono<ToolOutcomeCheck.Case> find(ExecutionOwner owner, String invocationId);

    void requireCurrentQueryInCurrentTransaction(ToolOutcomeCheck.Claim claim);

    Mono<ToolOutcomeEvidence> query(ToolOutcomeCheck.Claim claim, java.util.function.Supplier<ToolOutcomeEvidence> read);
}
