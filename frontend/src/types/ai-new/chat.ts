import type {ExecutionError} from './conversation';

export type ExecutionStatus =
  | 'ACCEPTED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'INTERRUPTED'
  | 'TIMED_OUT'
  | 'OUTCOME_UNKNOWN'
  | 'CANCELLED';

/** Only the text fields consumed by this page; references and secrets stay server-side. */
export interface ChatTurnResult {
  turn: {
    turnId: string;
    conversationId: string;
    sequence: number;
    kind: 'MESSAGE' | 'REGENERATION';
    status: 'PREPARING' | 'READY' | 'ACCEPTED' | 'REJECTED';
    input: Array<{ role: 'USER'; parts: Array<{ text: string }> }>;
    idempotencyKey: { key: string };
    rejectionError: ExecutionError | null;
    createdAt: string;
  };
  execution: null | {
    executionId: string;
    status: ExecutionStatus;
    result: null | { output: Array<{ text: string }> };
    error: ExecutionError | null;
  };
}

/** Retained unchanged for an explicit replay, including the original version. */
export interface ChatSubmission {
  expectedVersion: number;
  text: string;
  externalTransferConfirmed: true;
}

export interface PendingChatCommand {
  key: string;
  body: ChatSubmission;
}
