package com.arte.ai.model.conversation;

import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.validation.ChatContractChecks;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.List;

/**
 * 一次聊天提交快照。sequence 是提交顺序，重新生成也有自己的提交记录；input 仅是本轮用户输入，不含服务端历史。
 * conversationVersion 固定准备上下文所依据的会话版本，version 是本记录条件更新版本。
 * slotReleasedAt 表示串行提交占位已释放，不能用释放占位来推断模型未执行／无费用。
 */
public record Turn(
        String turnId,
        String conversationId,
        ExecutionScope scope,
        long sequence,
        long conversationVersion,
        long version,
        TurnKind kind,
        TurnStatus status,
        List<Message> input,
        ModelOptions modelOptions,
        String regeneratesTurnId,
        String contextSnapshotId,
        String executionId,
        IdempotencyKey idempotencyKey,
        ExecutionError rejectionError,
        Instant createdAt,
        Instant updatedAt,
        Instant slotReleasedAt
) {
    public Turn {
        turnId = ChatContractChecks.identifier(turnId, 64, "turnId");
        conversationId = ChatContractChecks.identifier(conversationId, 64, "conversationId");
        scope = ChatContractChecks.scope(scope);
        ChatContractChecks.positive(sequence, "sequence");
        ChatContractChecks.positive(conversationVersion, "conversationVersion");
        ChatContractChecks.positive(version, "version");
        kind = ContractChecks.required(kind, "kind");
        status = ContractChecks.required(status, "status");
        input = List.copyOf(ContractChecks.required(input, "input"));
        if (input.isEmpty() || input.stream().anyMatch(message -> message.role() != MessageRole.USER
                || message.parts().stream().anyMatch(part -> !(part instanceof TextPart)))) {
            throw new IllegalArgumentException("turn input must contain only user text messages");
        }
        modelOptions = ContractChecks.required(modelOptions, "modelOptions");
        regeneratesTurnId = ChatContractChecks.optionalIdentifier(regeneratesTurnId, 64, "regeneratesTurnId");
        if (kind == TurnKind.MESSAGE && regeneratesTurnId != null
                || kind == TurnKind.REGENERATION && (regeneratesTurnId == null || regeneratesTurnId.equals(turnId))) {
            throw new IllegalArgumentException("regeneratesTurnId must match submission kind and refer to another turn");
        }
        contextSnapshotId = ChatContractChecks.optionalIdentifier(contextSnapshotId, 64, "contextSnapshotId");
        executionId = ChatContractChecks.executionId(executionId);
        idempotencyKey = ContractChecks.required(idempotencyKey, "idempotencyKey");
        ChatContractChecks.identifier(idempotencyKey.key(), 128, "idempotencyKey");
        ChatContractChecks.digest(idempotencyKey.requestDigest(), "requestDigest");
        String operation = kind == TurnKind.MESSAGE ? "chat.turn.submit" : "chat.turn.regenerate";
        if (!operation.equals(idempotencyKey.operation()))
            throw new IllegalArgumentException("idempotency operation must match turn kind");
        if (rejectionError != null) {
            ChatContractChecks.identifier(rejectionError.code(), 128, "rejectionCode");
            ChatContractChecks.identifier(rejectionError.failureStage(), 64, "rejectionStage");
            ChatContractChecks.optionalIdentifier(rejectionError.correlationId(), 128, "rejectionCorrelationId");
            if (rejectionError.sideEffectStatus() != SideEffectStatus.NONE || rejectionError.resultCertainty() != ResultCertainty.CONFIRMED) {
                throw new IllegalArgumentException("uncertain acceptance must be reconciled, not marked rejected");
            }
        }
        ChatContractChecks.times(createdAt, updatedAt);
        if (slotReleasedAt != null && (slotReleasedAt.isBefore(createdAt) || slotReleasedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("slotReleasedAt must be within submission timestamps");
        }
        boolean valid = switch (status) {
            case PREPARING ->
                    contextSnapshotId == null && executionId == null && rejectionError == null && slotReleasedAt == null;
            case READY ->
                    contextSnapshotId != null && executionId == null && rejectionError == null && slotReleasedAt == null;
            case ACCEPTED -> contextSnapshotId != null && executionId != null && rejectionError == null;
            case REJECTED -> executionId == null && rejectionError != null && slotReleasedAt != null;
        };
        if (!valid)
            throw new IllegalArgumentException("turn status, references, rejection and slot state are inconsistent");
    }

    public boolean occupiesConversationSlot() {
        return slotReleasedAt == null;
    }
}
