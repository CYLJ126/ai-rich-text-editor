package com.arte.ai.model.conversation;

import com.arte.ai.model.execution.ModelExecution;
import com.arte.base.validation.ContractChecks;

/**
 * 提交记录与权威执行结果的组合查询，不在聊天表复制生成终态。
 */
public record ChatTurnResult(Turn turn, ModelExecution execution) {
    public ChatTurnResult {
        turn = ContractChecks.required(turn, "turn");
        if (turn.status() == TurnStatus.ACCEPTED) {
            execution = ContractChecks.required(execution, "execution");
            if (!turn.executionId().equals(execution.executionId()) || !turn.scope().equals(execution.scope()))
                throw new IllegalArgumentException("execution must match turn");
        } else if (execution != null) {
            throw new IllegalArgumentException("unaccepted turn cannot contain execution");
        }
    }
}
