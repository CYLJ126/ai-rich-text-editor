import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {
  AiScope,
  AnswerCandidate,
  ConversationTurnResponse,
  ExecutionError,
  ModelResult,
  SubmitChatRequest
} from './types';

export type RunState =
  'ACCEPTED'
  | 'RUNNING'
  | 'WAITING_TOOL'
  | 'WAITING_APPROVAL'
  | 'PENDING_RECONCILIATION'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT';

export interface RunCheckpoint {
  roundIndex: number;
  phase: 'READY_MODEL' | 'WAITING_MODEL' | 'WAITING_TOOLS' | 'WAITING_APPROVAL' | 'RECONCILING' | 'FINISHED';
  children: string[];
}

export interface ToolChatStatus {
  runId: string;
  state: RunState;
  version: number;
  checkpoint: RunCheckpoint;
  modelSteps: number;
  toolSteps: number;
  resultAvailable: boolean;
  cancellationRequested: boolean;
  error: ExecutionError | null;
  acceptedAt: string;
  updatedAt: string;
}

export interface RunBudget {
  runId: string;
  currency: string;
  limit: string;
  charged: string;
  held: string;
  available: string;
  blocked: boolean;
  version: number;
}

export interface RunEvent {
  runId: string;
  sequence: number;
  state: RunState;
  checkpoint: RunCheckpoint;
  occurredAt: string;
}

export interface RunAnswer {
  runId: string;
  finalInvocationId: string;
  result: { value: ModelResult }
}

export interface ToolChatChoice {
  toolBindingIds: string[];
  maxModelRounds: number;
  maxToolCalls: number;
  maxConcurrentTools: number;
  budgetLimits: { currency: string; amount: string }[];
  /** 刷新资料时服务端再次保证全部固定工具均为只读。 */
  readOnly?: boolean;
}

export interface SubmitToolChatRequest extends Omit<SubmitChatRequest, 'budgetRef'>, Omit<ToolChatChoice, 'readOnly'> {
  modelBudgetRef: string
}

export interface RunActions {
  runId: string;
  text: string;
  answeredAt: string;
  readOnlyBindingIds: string[];
  writeBindingIds: string[];
}

const query = <T>(path: string, scope: AiScope, runId: string, options?: AiRequestOptions) =>
  postResult<T>(`tool-chat/${path}`, {scope, runId}, options);
export const getRunStatus = (scope: AiScope, id: string, options?: AiRequestOptions) => query<ToolChatStatus>('getRunStatus', scope, id, options);
export const getRunResult = (scope: AiScope, id: string, options?: AiRequestOptions) => query<RunAnswer>('getRunResult', scope, id, options);
export const getRunBudget = (scope: AiScope, id: string, options?: AiRequestOptions) => query<RunBudget[]>('getRunBudget', scope, id, options);
export const getRunActions = (scope: AiScope, id: string, options?: AiRequestOptions) => query<RunActions>('getRunActions', scope, id, options);
export const discoverToolChat = (scope: AiScope, options?: AiRequestOptions) => postResult<{
  enabled: boolean
}>('tool-chat/discoverToolChat', {scope}, options);
export const replayRunEvents = (scope: AiScope, runId: string, afterSequence: number, options?: AiRequestOptions) =>
  postResult<RunEvent[]>('tool-chat/replayRunEvents', {scope, runId, afterSequence, limit: 100}, options);
export const submitToolChat = (request: SubmitToolChatRequest, key: string, readOnly = false, options?: AiRequestOptions) =>
  postResult<{
    executionId: string;
    kind: 'RUN';
    acceptedAt: string
  }>(`tool-chat/${readOnly ? 'submitReadOnlyToolChat' : 'submitToolChat'}`, request, options, requireIdempotencyKey(key));
export const cancelRun = (scope: AiScope, runId: string, key: string, options?: AiRequestOptions) =>
  postResult<ToolChatStatus>('tool-chat/cancelRun', {scope, runId}, options, requireIdempotencyKey(key));
export const runTerminal = (state: RunState) => ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(state);
/** 兼容旧 Turn；新 Turn 的 candidates 即使为空也不退回 Invocation 投影。 */
export const turnCandidates = (turn: ConversationTurnResponse): AnswerCandidate[] => turn.candidates ?? turn.invocationIds.map(executionId => ({
  kind: 'INVOCATION',
  executionId
}));
export const candidateKey = (candidate: AnswerCandidate) => `${candidate.kind}:${candidate.executionId}`;
