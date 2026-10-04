package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.GenerationEvent;

import java.util.List;
import java.util.Objects;

/**
 * AI 平台持久化事件负载；供应商增量先组成有界批次，由平台信封赋予耐久游标。类型名及版本由编码器白名单映射。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public sealed interface ExecutionPayload extends ExecutionEvent.Payload permits ExecutionPayload.OutputBatch,
        ExecutionPayload.Status, ExecutionPayload.Control, ExecutionPayload.Terminal {
    @Override
    default ExecutionEvent.Kind eventKind() {
        return switch (this) {
            case OutputBatch ignored -> ExecutionEvent.Kind.OUTPUT;
            case Status status -> switch (status.state()) {
                case ACCEPTED -> ExecutionEvent.Kind.ACCEPTED;
                case RUNNING -> ExecutionEvent.Kind.STARTED;
                default -> ExecutionEvent.Kind.CHECKPOINT;
            };
            case Control ignored -> ExecutionEvent.Kind.CONTROL;
            case Terminal ignored -> ExecutionEvent.Kind.TERMINAL;
        };
    }

    record OutputBatch(List<GenerationEvent> events) implements ExecutionPayload {
        public OutputBatch {
            events = ContractChecks.list(events, "events", 1, ContractChecks.MAX_ITEMS);
            ContractChecks.require(events.stream().mapToLong(GenerationEvent::characterCount).sum()
                    <= ContractChecks.MAX_TEXT_CHARS, "Output batch exceeds character limit");
        }
    }

    record Status(Invocation.State state) implements ExecutionPayload {
        public Status {
            Objects.requireNonNull(state, "state");
            ContractChecks.require(!state.terminal(), "Final state belongs to terminal payload");
        }
    }

    record Control(ControlReceipt receipt) implements ExecutionPayload {
        public Control {
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    record Terminal(Invocation.State state, ResultRef result, ExecutionError error) implements ExecutionPayload {
        public Terminal {
            Objects.requireNonNull(state, "state");
            ContractChecks.require(state.terminal(), "Terminal event requires final state");
            ContractChecks.require(state != Invocation.State.SUCCEEDED || result != null && !result.partial() && error == null,
                    "Success requires complete result");
            ContractChecks.require(state == Invocation.State.SUCCEEDED || result == null || result.partial(),
                    "Unsuccessful result must be partial");
            ContractChecks.require(state == Invocation.State.SUCCEEDED || state == Invocation.State.CANCELLED || error != null,
                    "Failure requires error facts");
            ContractChecks.require(state != Invocation.State.UNKNOWN || error.certainty() == ExecutionError.Certainty.UNKNOWN,
                    "Unknown terminal outcome requires unknown certainty");
            ContractChecks.require(error == null || (state == Invocation.State.UNKNOWN)
                    == (error.certainty() == ExecutionError.Certainty.UNKNOWN), "Unknown certainty requires UNKNOWN terminal state");
        }
    }
}
