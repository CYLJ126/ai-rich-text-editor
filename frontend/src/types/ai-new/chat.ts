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
    regeneratesTurnId: string | null;
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
    partialText?: string;
    partialSequence?: number;
  };
}

/** Retained unchanged for an explicit replay, including the original version. */
export interface ChatSubmission {
  expectedVersion: number;
  text: string;
  externalTransferConfirmed: true;
}

export type PendingChatCommand =
  | { key: string; kind?: 'MESSAGE'; body: ChatSubmission }
  | { key: string; kind: 'REGENERATION'; body: ChatSubmission & { originalTurnId: string } };

export type CancellationStatus = 'REQUEST_ACCEPTED' | 'CANCELLING' | 'CANCELLED' | 'UNCONFIRMED' | 'ALREADY_COMPLETED';
