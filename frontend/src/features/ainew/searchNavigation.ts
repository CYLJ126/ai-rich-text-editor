import type {AnswerCandidate} from '@/services/arte-ai';
import {
  AiApiError,
  type AiScope,
  type ConversationResponse,
  type ConversationTurnResponse,
  getConversation,
  getInvocationSnapshot,
  type InvocationSnapshotResponse,
  queryTurnsAround
} from '@/services/arte-ai';
import type {
  ConversationSearchItem,
  ConversationTurnsAroundPage
} from '@/services/arte-ai/conversation-search-contract';
import {SearchTargetUnavailable} from './useConversationSearch';
import {getRunResult, turnCandidates} from '@/services/arte-ai/toolChat';
import type {InvocationView} from './useChatSession';

export interface LocatedHistory {
  id: string;
  entryKey: string;
  conversationId: string;
  turnId: string;
  messageId: string;
  invocationId: string | null;
  candidate?: AnswerCandidate | null;
  conversation: ConversationResponse;
  page: ConversationTurnsAroundPage;
  views: Record<string, InvocationView>;
}

export function validateSnapshot(value: InvocationSnapshotResponse, turn: ConversationTurnResponse, id: string) {
  if (value.missing || !value.status || value.invocationId !== id || value.status.invocationId !== id
    || value.status.conversation?.conversationId !== turn.conversationId || value.status.conversation.turnId !== turn.turnId
    || !turn.invocationIds.includes(id) || value.result && value.result.invocationId !== id) throw new SearchTargetUnavailable('entry');
  return {status: value.status, result: value.result ?? undefined};
}

export async function historyViews(scope: AiScope, page: ConversationTurnsAroundPage, signal: AbortSignal, target?: {
  turnId: string;
  invocationId: string | null; candidate?: AnswerCandidate | null
}) {
  const views: Record<string, InvocationView> = {};
  await Promise.all(page.records.map(async turn => {
    const id = target?.turnId === turn.turnId && target.invocationId ? target.invocationId : turn.selectedInvocationId ?? turn.invocationIds.at(-1);
    if (!id) return;
    const candidate = target?.turnId === turn.turnId && target.invocationId ? target.candidate : turn.selectedCandidate;
    if (candidate?.kind === 'TOOL_CHAT_RUN') {
      if (!turnCandidates(turn).some(c => c.kind === candidate.kind && c.executionId === candidate.executionId)) throw new SearchTargetUnavailable('entry');
      const answer = (await getRunResult(scope, candidate.executionId, {signal})).body.data;
      if (answer.runId !== candidate.executionId || answer.finalInvocationId !== id) throw new SearchTargetUnavailable('entry');
      views[id] = {result: {invocationId: id, kind: 'GENERATION', result: answer.result}};
      return;
    }
    try {
      views[id] = validateSnapshot(await getInvocationSnapshot({scope, invocationId: id}, {signal}), turn, id);
    } catch (error) {
      if (signal.aborted || (error instanceof AiApiError && [401, 403].includes(error.httpStatus)) || id === target?.invocationId) throw error;
      views[id] = {error};
    }
  }));
  return views;
}

export async function currentConversation(scope: AiScope, id: string, signal: AbortSignal) {
  try {
    const {body} = await getConversation({scope, conversationId: id}, {signal});
    if (body.data.conversationId !== id) throw new Error('Conversation identity mismatch');
    if (body.data.state !== 'ACTIVE') throw new SearchTargetUnavailable('conversation');
    return body.data;
  } catch (error) {
    if (error instanceof AiApiError && [403, 404].includes(error.httpStatus)) throw new SearchTargetUnavailable('conversation');
    throw error;
  }
}

/** 读取准备不改变当前选择；一次版本冲突只重新读取目标详情一次。 */
export async function locateSearchMessage(scope: AiScope, item: ConversationSearchItem, signal: AbortSignal): Promise<LocatedHistory> {
  if (item.matchKind !== 'MESSAGE') throw new SearchTargetUnavailable('entry');
  let conversation = await currentConversation(scope, item.conversationId, signal);
  let page: ConversationTurnsAroundPage;
  try {
    page = await around(conversation);
  } catch (error) {
    if (!(error instanceof AiApiError && error.httpStatus === 409 && error.code === '205030')) throw error;
    conversation = await currentConversation(scope, item.conversationId, signal);
    page = await around(conversation);
  }
  const turn = page.records.find(value => value.turnId === item.turnId);
  if (!turn || turn.conversationId !== item.conversationId) throw new SearchTargetUnavailable('entry');
  if (item.invocationId ? item.candidate?.kind === 'TOOL_CHAT_RUN' ? !turnCandidates(turn).some(c => c.kind === 'TOOL_CHAT_RUN' && c.executionId === item.candidate?.executionId) : !turn.invocationIds.includes(item.invocationId) : turn.userMessage.messageId !== item.messageId)
    throw new SearchTargetUnavailable('entry');
  const views = await historyViews(scope, page, signal, item);
  if (item.invocationId) {
    const view = views[item.invocationId];
    if (view?.result?.kind !== 'GENERATION' || !view.result.result.value.outputs.some(message => message.messageId === item.messageId)) throw new SearchTargetUnavailable('entry');
  }
  if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
  return {
    id: crypto.randomUUID(), entryKey: item.entryKey, conversationId: item.conversationId, turnId: item.turnId,
    messageId: item.messageId, invocationId: item.invocationId, candidate: item.candidate, conversation, page, views
  };

  async function around(c: ConversationResponse) {
    try {
      const {body} = await queryTurnsAround({
        scope,
        conversationId: c.conversationId,
        turnId: item.turnId!,
        expectedVersion: c.version,
        size: 10
      }, {signal});
      if (body.data.conversationVersion !== c.version || body.data.records.some(turn => turn.conversationId !== c.conversationId)) throw new Error('History version mismatch');
      return body.data;
    } catch (error) {
      if (error instanceof AiApiError && error.httpStatus === 404) throw new SearchTargetUnavailable(error.code === '205016' ? 'conversation' : 'entry');
      throw error;
    }
  }
}
