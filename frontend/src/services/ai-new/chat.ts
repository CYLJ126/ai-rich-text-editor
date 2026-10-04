import {z} from 'zod';
import type {
  CancellationStatus,
  ChatTurnResult,
  PendingChatCommand,
  RagContext,
  RetrievalPreview,
  RetrievalSelection
} from '@/types/ai-new/chat';
import type {WorkspaceSelection} from '@/types/ai-new/conversation';
import {readContract} from './contracts';
import {AiNewApiError, requestAiNew} from './request';

const error = z.object({
  code: z.string(), failureStage: z.string(), retryable: z.boolean(),
  sideEffectStatus: z.enum(['NONE', 'OCCURRED', 'UNKNOWN']), resultCertainty: z.enum(['CONFIRMED', 'UNKNOWN']),
});
const text = z.object({text: z.string()});
const ragContextSchema = z.object({
  contentDigest: z.string().regex(/^sha256:[a-f0-9]{64}$/),
  expiresAt: z.iso.datetime(),
  messages: z.array(z.object({role: z.enum(['USER', 'ASSISTANT', 'SYSTEM']), parts: z.array(text).min(1)})).min(1),
  budget: z.object({usedInputBytes: z.number().int().nonnegative(), inputByteLimit: z.number().int().positive(),
    estimatedInputTokens: z.number().int().nonnegative(), inputTokenLimit: z.number().int().positive()}),
  fragments: z.array(z.object({citationId: z.string(), content: z.string(), truncated: z.boolean(), coverageDescription: z.string(),
    source: z.object({resource: z.object({resourceType: z.string(), resourceId: z.string(), version: z.string(),
      rangeRef: z.string().nullable(), contentDigest: z.string().nullable()})})})),
});
const previewSchema = z.object({previewId: z.string(), conversationId: z.string(), conversationVersion: z.number().int().positive(), context: ragContextSchema});

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
    resourceContext: ragContextSchema.nullable().optional(),
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
      ...(regenerating ? {originalTurnId: command.body.originalTurnId} : {text: command.body.text, ...(command.body.previewId ? {previewId: command.body.previewId, expectedContextDigest: command.body.expectedContextDigest} : {})}),
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

export async function getRetrievalArticles(scope: WorkspaceSelection, signal?: AbortSignal): Promise<Array<{id: string; title: string; version: string}>> {
  return readContract(z.array(z.object({id: z.string(), title: z.string(), version: z.string()})),
    await requestAiNew('/conversations/rag/articles', {params: selection(scope), signal}));
}
export async function previewChatRetrieval(scope: WorkspaceSelection, id: string, expectedVersion: number, text: string, retrieval: RetrievalSelection): Promise<RetrievalPreview> {
  const result = readContract(previewSchema, await requestAiNew(`/conversations/${encodeURIComponent(id)}/rag-preview`, {
    method: 'POST', data: {...selection(scope), expectedVersion, text, retrieval},
  }));
  if (result.conversationId !== id || result.conversationVersion !== expectedVersion) throw new AiNewApiError(502, null);
  return result;
}
export async function getRetrievalPreview(scope: WorkspaceSelection, id: string, previewId: string): Promise<RetrievalPreview> {
  const result = readContract(previewSchema, await requestAiNew(`/conversations/${encodeURIComponent(id)}/rag-preview/${encodeURIComponent(previewId)}`, {params: selection(scope)}));
  if (result.conversationId !== id || result.previewId !== previewId) throw new AiNewApiError(502, null);
  return result;
}
export async function getChatContext(scope: WorkspaceSelection, id: string, turnId: string): Promise<RagContext | null> {
  const result = readContract(z.object({conversationId: z.string(), resourceContext: ragContextSchema.nullable()}),
    await requestAiNew(`${path(id)}/${encodeURIComponent(turnId)}/context`, {params: selection(scope)}));
  if (result.conversationId !== id) throw new AiNewApiError(502, null);
  return result.resourceContext;
}
