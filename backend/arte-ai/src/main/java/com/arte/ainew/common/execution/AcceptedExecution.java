package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 服务端可靠受理回执
 * <p>
 * 只能在受理记录与可恢复派发依据提交后返回，不表示生成或业务保存成功。
 * 查询地址由 HTTP 适配层根据 executionId 生成，不把部署 URL 写入持久化契约。
 *
 * @param executionId 已可靠受理的执行对象标识，结合 kind 定位对应调用、任务或流程，用于后续状态、事件及结果查询；不是 Attempt 标识或查询 URL。
 * @param kind        受理对象的种类，不能为空：INVOCATION 表示逻辑调用，JOB 表示后台任务，RUN 表示多步执行流程；用于区分后续查询和处理的对象类型。
 * @param acceptedAt  执行对象首次被可靠受理的时间，不能为空，幂等重放沿用原受理时间；不表示实际开始执行或执行完成的时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record AcceptedExecution(String executionId, Kind kind, Instant acceptedAt) implements Serializable {

    /**
     * 已受理执行对象的种类，用于解释 executionId 的指向，不表示执行状态。
     */
    public enum Kind {
        /**
         * 一次逻辑能力调用，可关联模型生成或工具执行；实际执行尝试由 Attempt 独立记录。
         */
        INVOCATION,
        /**
         * 可恢复的后台任务或工作项，由任务处理流程推进。
         */
        JOB,
        /**
         * 多步执行流程，协调多个步骤或子调用，例如 Tool Chat Run。
         */
        RUN
    }

    public AcceptedExecution {
        ContractChecks.id(executionId, "executionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
