package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 服务端可靠受理回执。只能在受理记录与可恢复派发依据提交后返回，不表示生成或业务保存成功。
 * 查询地址由 HTTP 适配层根据 executionId 生成，不把部署 URL 写入持久化契约。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record AcceptedExecution(String executionId, Kind kind, Instant acceptedAt) implements Serializable {
    public enum Kind { INVOCATION, JOB, RUN }

    public AcceptedExecution {
        ContractChecks.id(executionId, "executionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
