package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.execution.Usage;

import java.util.Objects;

/**
 * 同一次订阅的增量与结束事实，不是平台耐久事件。零或多个 Delta 后必须有且仅有一个 Result 或 Failure。
 * 结束信号之后不得再发信号；普通 onComplete 缺少结束信号时视为中断。协议约束由适配实现验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public sealed interface GenerationSignal permits GenerationSignal.Delta, GenerationSignal.Result, GenerationSignal.Failure {
    record Delta(GenerationEvent event) implements GenerationSignal {
        public Delta {
            Objects.requireNonNull(event, "event");
        }
    }

    /**
     * 即使 complete=false 也保留输出事实；是否成功由 Coordinator 输出校验和终态提交决定。
     */
    record Result(ModelResult result) implements GenerationSignal {
        public Result {
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * 已发生的调用失败保留安全错误、已知用量和可选部分输出；未知用量不能变成零。
     */
    record Failure(ExecutionError error, Usage usage, ModelResult partialResult) implements GenerationSignal {
        public Failure {
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(usage, "usage");
            if (partialResult != null && partialResult.complete()) {
                throw new IllegalArgumentException("Failure output must be partial");
            }
            if (partialResult != null && !partialResult.usage().equals(usage)) {
                throw new IllegalArgumentException("Failure and partial output usage disagree");
            }
        }
    }
}
