package com.arte.base.model.execution;

import com.arte.base.validation.ContractChecks;

import java.net.URI;

/**
 * 可靠受理回执，所有字段必填；种类与初始状态由所属模块定义。
 * 查询地址支持站内绝对路径或 HTTP(S) URL，不带凭据、查询串及片段。
 * 值校验不能保证已经耐久受理；执行入口只能在持久化受理成功后返回本回执。
 */
public record AcceptedExecution(
        String executionId,
        String executionKind,
        String initialState,
        URI statusUri,
        URI eventsUri
) {

    public AcceptedExecution {
        executionId = ContractChecks.identifier(executionId, "executionId");
        executionKind = ContractChecks.identifier(executionKind, "executionKind");
        initialState = ContractChecks.identifier(initialState, "initialState");
        statusUri = ContractChecks.queryLocation(statusUri, "statusUri");
        eventsUri = ContractChecks.queryLocation(eventsUri, "eventsUri");
    }
}
