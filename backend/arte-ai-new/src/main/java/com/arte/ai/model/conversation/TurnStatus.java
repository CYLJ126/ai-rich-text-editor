package com.arte.ai.model.conversation;

/**
 * 聊天提交的准备／关联状态。ACCEPTED 仅表示关联可靠受理，模型终态从 ExecutionStore 查询。
 * REJECTED 只适用于没有 executionId 的受理前失败；不得将已受理调用的失败覆盖为 REJECTED。
 */
public enum TurnStatus {
    PREPARING,
    READY,
    ACCEPTED,
    REJECTED;

    public boolean canTransitionTo(TurnStatus next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case PREPARING -> next == READY || next == REJECTED;
            case READY -> next == ACCEPTED || next == REJECTED;
            case ACCEPTED, REJECTED -> false;
        };
    }
}
