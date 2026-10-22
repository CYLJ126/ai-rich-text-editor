import {type AiRequestOptions, postResult} from './request';
import type {AiScope, InvocationState} from './types';

export interface ToolObservationQuery {
  from: string;
  until: string;
  bindingId?: string;
  toolId?: string;
  state?: InvocationState;
}

export interface ToolExecutionItem {
  invocationId: string;
  traceId: string;
  parentExecutionId: string | null;
  source: 'INDEPENDENT' | 'CHAT_CHILD';
  toolId: string;
  toolVersion: string;
  bindingId: string;
  title: string;
  providerId: string;
  state: InvocationState;
  version: string;
  acceptedAt: string;
  updatedAt: string;
  attemptCount: number;
  retryCount: number;
  errorCode: string | null;
  billingMode: 'FREE' | 'CHARGE';
  cost: {
    state: string;
    currency: string | null;
    settledAmount: string | null;
    reservedAmount: string | null;
    unknown: boolean
  };
  timings: {
    admissionToAttemptMillis: number | null;
    approvalWaitMillis: number | null;
    attemptSpanMillis: number | null;
    terminalSpanMillis: number | null;
    elapsedMillis: number | null;
  };
  inputBytes: number | null;
  outputBytes: number | null;
}

export interface ToolExecutionPage {
  records: ToolExecutionItem[];
  nextCursor: string | null;
  exhausted: boolean;
  scanned: number;
  scanLimit: number;
  historicalIncomplete: boolean;
  observedAt: string;
  query: ToolObservationQuery;
  scope: 'CURRENT_SUBJECT';
}

export interface ToolStatistics {
  query: ToolObservationQuery;
  scope: 'CURRENT_SUBJECT';
  observedAt: string;
  invocations: number;
  attempts: number;
  retries: number;
  states: Record<InvocationState, number>;
  determinedTerminals: number;
  successRate: string | null;
  freeCalls: number;
  chargedCalls: number;
  unknownCostCalls: number;
  knownZeroCostCalls: number;
  currencies: { currency: string; settledAmount: string; reservedAmount: string }[];
  terminalSpanP50Millis: number | null;
  terminalSpanP95Millis: number | null;
  knownInputBytes: number;
  unknownInputBytesCalls: number;
  outputBytes: number | null;
  scanned: number;
  scanLimit: number;
  truncated: boolean;
  historicalIncomplete: boolean;
}

/** Scope 只描述当前空间；主体由服务端身份决定，不允许传入其他用户或管理视角。 */
export const listToolExecutions = (scope: AiScope, query: ToolObservationQuery, cursor: string | null, options?: AiRequestOptions) =>
  postResult<ToolExecutionPage>('tool/listExecutions', {scope, ...query, cursor, limit: 10}, options);
export const getToolStatistics = (scope: AiScope, query: ToolObservationQuery, options?: AiRequestOptions) =>
  postResult<ToolStatistics>('tool/getStatistics', {scope, ...query}, options);
