package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

/**
 * 专有结果字节端口，目前仅声明。先持久化字节，再由 ExecutionStore 原子提交结果引用和终态。
 * 不在此推进 Invocation；终态事务失败产生的孤立结果由保留策略清理，不能先删仍被引用的结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public interface ExecutionResultStore {

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
