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
    resourceContext?: RagContext | null;
    partialText?: string;
    partialSequence?: number;
  };
}

/** Retained unchanged for an explicit replay, including the original version. */
export interface ChatSubmission {
  expectedVersion: number;
  text: string;
  externalTransferConfirmed: true;
  previewId?: string;
  expectedContextDigest?: string;
}

export type PendingChatCommand =
  | { key: string; kind?: 'MESSAGE'; body: ChatSubmission }
  | { key: string; kind: 'REGENERATION'; body: ChatSubmission & { originalTurnId: string } };

export type CancellationStatus = 'REQUEST_ACCEPTED' | 'CANCELLING' | 'CANCELLED' | 'UNCONFIRMED' | 'ALREADY_COMPLETED';

export type RetrievalMode = 'NONE' | 'ARTICLE_FULL_TEXT' | 'SELECTED_ARTICLES' | 'ARTICLE_LIBRARY';
export interface RetrievalSelection { mode: RetrievalMode; articleIds: string[]; semanticSearch: boolean; maxResults: number }
export interface RagFragment {
  citationId: string;
  source: { resource: { resourceType: string; resourceId: string; version: string; rangeRef: string | null; contentDigest: string | null } };
  content: string;
  truncated: boolean;
  coverageDescription: string;
}
export interface RagContext {
  contentDigest: string;
  fragments: RagFragment[];
  messages: Array<{role: 'USER' | 'ASSISTANT' | 'SYSTEM'; parts: Array<{text: string}>}>;
  expiresAt: string;
  budget: {usedInputBytes: number; inputByteLimit: number; estimatedInputTokens: number; inputTokenLimit: number};
}
export interface RetrievalPreview { previewId: string; conversationId: string; conversationVersion: number; context: RagContext }
