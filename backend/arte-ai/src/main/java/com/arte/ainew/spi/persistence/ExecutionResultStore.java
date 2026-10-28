package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

/**
 * 专有结果字节及内部恢复读取端口。先持久化字节，再由 ExecutionStore 原子提交结果引用和终态。
 * 不在此推进 Invocation；终态事务失败产生的孤立结果由保留策略清理，不能先删仍被引用的结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public interface ExecutionResultStore {

    /**
     * 仅供耐久恢复读取同一次尝试的已保存字节，不授予对外可见性；对外仍必须沿 Invocation.result 读取。
     */
    record StagedResult(ResultRef reference, InvocationResult result) {
    }

    default Mono<StagedResult> staged(ExecutionOwner owner, String invocationId, String attemptId, String resultKey) {
        return Mono.error(new UnsupportedOperationException("Staged result recovery is unavailable"));
    }

    default Mono<java.util.Map<String, InvocationResult>> findAll(ExecutionOwner owner, java.util.Map<String, ResultRef> references) {
        return reactor.core.publisher.Flux.fromIterable(references.entrySet()).concatMap(entry ->
                        find(owner, entry.getKey(), entry.getValue()).map(value -> java.util.Map.entry(entry.getKey(), value)))
                .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue);
    }


    /**
     * owner＋invocationId＋attemptId＋resultKey 防重，同键不同内容冲突；部分／完整输出使用不同键。
     * 返回已保存字节的固定类型别名、版本、摘要及 partial 标记，不能相信调用者自报摘要或完整性。
     */
    Mono<StoreOutcome<ResultRef>> put(ExecutionOwner owner, String invocationId, String attemptId,
                                      String resultKey, InvocationResult result);

    /**
     * 检查归属、可信类型白名单、Schema 及摘要；损坏明确 onError，不存在返回 empty。
     */
    Mono<InvocationResult> find(ExecutionOwner owner, String invocationId, ResultRef reference);
}
