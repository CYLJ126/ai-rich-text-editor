import {z} from 'zod';
import type {CancellationStatus, ChatTurnResult, PendingChatCommand} from '@/types/ai-new/chat';
import type {WorkspaceSelection} from '@/types/ai-new/conversation';
import {readContract} from './contracts';
import {AiNewApiError, requestAiNew} from './request';

const error = z.object({
  code: z.string(), failureStage: z.string(), retryable: z.boolean(),
  sideEffectStatus: z.enum(['NONE', 'OCCURRED', 'UNKNOWN']), resultCertainty: z.enum(['CONFIRMED', 'UNKNOWN']),
});
const text = z.object({text: z.string()});
export const turnSchema = z.object({
  turn: z.object({
    turnId: z.string().min(1), conversationId: z.string().min(1),
    sequence: z.number().int().positive().refine(Number.isSafeInteger),
    kind: z.enum(['MESSAGE', 'REGENERATION']), status: z.enum(['PREPARING', 'READY', 'ACCEPTED', 'REJECTED']),
    regeneratesTurnId: z.string().min(1).nullable(),
    input: z.array(z.object({role: z.literal('USER'), parts: z.array(text).min(1)})).min(1),
    idempotencyKey: z.object({key: z.string().min(1)}), rejectionError: error.nullable(), createdAt: z.iso.datetime(),
  }),
  execution: z.object({
    executionId: z.string().min(1),
    status: z.enum(['ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED', 'TIMED_OUT', 'OUTCOME_UNKNOWN', 'CANCELLED']),
    result: z.object({output: z.array(text).min(1)}).nullable(), error: error.nullable(),
    partialText: z.string().optional(),
    partialSequence: z.number().int().min(-1).refine(Number.isSafeInteger).optional(),
  }).nullable(),
}).refine(({turn, execution}) => (turn.status === 'ACCEPTED') === (execution !== null)
  && (turn.status !== 'REJECTED' || turn.rejectionError !== null)
  && (execution?.status !== 'SUCCEEDED' || execution.result !== null));

const path = (id: string) => `/conversations/${encodeURIComponent(id)}/turns`;
const selection = (scope: WorkspaceSelection) => ({tenantId: scope.tenantId, workspaceId: scope.workspaceId});

export async function getChatHistory(scope: WorkspaceSelection, id: string, beforeSequence?: number, signal?: AbortSignal): Promise<ChatTurnResult[]> {
  const result = readContract(z.array(turnSchema), await requestAiNew(path(id), {
    params: {...selection(scope), beforeSequence, limit: 20}, signal,
  }));
  if (result.some((item) => item.turn.conversationId !== id)) throw new AiNewApiError(502, null);
  return result;
}

export async function getChatTurn(scope: WorkspaceSelection, id: string, turnId: string, signal?: AbortSignal): Promise<ChatTurnResult> {
  const result = readContract(turnSchema, await requestAiNew(`${path(id)}/${encodeURIComponent(turnId)}`, {
    params: selection(scope), signal,
  }));
  if (result.turn.conversationId !== id || result.turn.turnId !== turnId) throw new AiNewApiError(502, null);
  return result;
}

export async function submitChat(scope: WorkspaceSelection, id: string, command: PendingChatCommand): Promise<ChatTurnResult> {
  const regenerating = command.kind === 'REGENERATION';
  const result = readContract(turnSchema, await requestAiNew(regenerating ? `/conversations/${encodeURIComponent(id)}/regenerate` : path(id), {
    method: 'POST', headers: {'Idempotency-Key': command.key},
    data: {
      ...selection(scope),
      expectedVersion: command.body.expectedVersion,
      ...(regenerating ? {originalTurnId: command.body.originalTurnId} : {text: command.body.text}),
      externalTransferConfirmed: command.body.externalTransferConfirmed
    },
  }));
  if (result.turn.conversationId !== id || result.turn.idempotencyKey.key !== command.key) throw new AiNewApiError(502, null);
  if (result.turn.kind !== (regenerating ? 'REGENERATION' : 'MESSAGE')
    || (regenerating && result.turn.regeneratesTurnId !== command.body.originalTurnId)) throw new AiNewApiError(502, null);
  return result;
}

export async function cancelChat(scope: WorkspaceSelection, id: string, turnId: string): Promise<CancellationStatus> {
  const result = readContract(z.object({status: z.enum(['REQUEST_ACCEPTED', 'CANCELLING', 'CANCELLED', 'UNCONFIRMED', 'ALREADY_COMPLETED'])}),
    await requestAiNew(`${path(id)}/${encodeURIComponent(turnId)}/cancel`, {method: 'POST', params: selection(scope)}));
  return result.status;
}
