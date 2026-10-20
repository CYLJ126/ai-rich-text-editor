import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {AiScope} from './types';

export type ApprovalDecision = 'APPROVE' | 'REJECT';

export interface ToolApprovalDetail {
  approvalId: string;
  invocationId: string;
  version: string;
  subjectDigest: string;
  mode: 'REQUESTER_CONFIRMATION' | 'INDEPENDENT_APPROVAL';
  state: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
  requesterSubjectId: string;
  approverSubjectIds: string[];
  title: string;
  toolVersion: string;
  bindingVersion: string;
  argumentsJson: string;
  configurationJson: string;
  sideEffect: string;
  expiresAt: string;
  executeNotAfter: string;
  decidedBy: string | null;
  decidedAt: string | null;
  invocationState: string;
  mayDecide: boolean;
  requester: boolean;
  serverTime: string;
}

export interface ApprovalSummary {
  approvalId: string;
  invocationId: string;
  title: string;
  mode: string;
  expiresAt: string;
  mayDecide: boolean;
}

export interface ApprovalReceipt {
  approvalId: string;
  invocationId: string;
  version: string;
  state: string;
  invocationState: string;
  decidedAt: string;
}

export interface ApprovalOperation {
  key: string;
  body: {
    scope: AiScope;
    approvalId: string;
    expectedVersion: string;
    subjectDigest: string;
    decision: ApprovalDecision
  };
}

export const getToolApproval = (scope: AiScope, locator: { approvalId: string } | {
  invocationId: string
}, options?: AiRequestOptions) =>
  postResult<ToolApprovalDetail>('tool/getApproval', {scope, ...locator}, options);
export const listPendingApprovals = (scope: AiScope, cursor?: string | null, options?: AiRequestOptions) =>
  postResult<{
    records: ApprovalSummary[];
    nextCursor: string | null;
    exhausted: boolean
  }>('tool/listPendingApprovals', {scope, cursor, limit: 10}, options);
export const decideToolApproval = (operation: ApprovalOperation, options?: AiRequestOptions) =>
  postResult<ApprovalReceipt>('tool/decideApproval', operation.body, options, requireIdempotencyKey(operation.key));
