import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {AiScope} from './types';

export interface ToolReviewDetail {
  invocationId: string;
  attemptId: string;
  operationKey: string;
  version: string;
  invocationVersion: string;
  state: 'CHECKING' | 'NEEDS_REVIEW' | 'UNRESOLVABLE' | 'RESOLVED';
  invocationState: string;
  title: string;
  argumentsJson: string;
  requesterSubjectId: string;
  checks: number;
  maxChecks: number;
  createdAt: string;
  nextCheckAt: string;
  autoExpiresAt: string;
  lastCode: string;
  responsibleSubjectId: string;
  nextStep: string;
  dueAt: string;
  unassigned: boolean;
  overdue: boolean;
  mayConfirm: boolean;
  mayClaim: boolean;
  evidenceSupported: boolean;
  serverTime: string;
  history: {
    commandKey: string;
    actorId: string;
    action: string;
    outcome: string;
    note: string;
    nextStep: string;
    recordedAt: string
  }[];
}

export interface ToolReviewEvidence {
  decision: 'CONFIRMED_SUCCEEDED' | 'CONFIRMED_FAILED' | 'PROVEN_NOT_EXECUTED' | 'STILL_UNKNOWN';
  reference: string | null;
  digest: string | null;
  sideEffect: string | null;
  outputJson: string | null;
}

export type ToolReviewPath =
  'claimReconciliation'
  | 'requestOutcomeCheck'
  | 'confirmOutcome'
  | 'markOutcomeUnresolvable';

export interface ToolReviewOperation {
  key: string;
  path: ToolReviewPath;
  requestJson: string
}

export const getToolReconciliation = (scope: AiScope, invocationId: string, managed = false, options?: AiRequestOptions) =>
  postResult<ToolReviewDetail>('tool/getReconciliation', {scope, invocationId, managed}, options);
export const listToolReconciliations = (scope: AiScope, cursor: string | null, options?: AiRequestOptions) =>
  postResult<{
    records: ToolReviewDetail[];
    nextCursor: string | null;
    exhausted: boolean
  }>('tool/listReconciliations', {scope, cursor, limit: 10}, options);
export const getToolOutcomeEvidence = (scope: AiScope, invocationId: string, options?: AiRequestOptions) =>
  postResult<ToolReviewEvidence>('tool/getOutcomeEvidence', {scope, invocationId}, options);
export const sendToolReview = (operation: ToolReviewOperation, options?: AiRequestOptions) =>
  postResult<{
    outcome: string;
    invocationState: string
  }>(`tool/${operation.path}`, JSON.parse(operation.requestJson), options, requireIdempotencyKey(operation.key));
