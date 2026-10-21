import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import {type LocatedHistory, locateSearchMessage} from './searchNavigation';
import {SearchTargetUnavailable} from './useConversationSearch';
import {useLocatedHistory} from './useLocatedHistory';
import {getRunResult} from '@/services/arte-ai/toolChat';
import {toolChatAnswer} from './toolChat.testdata';

vi.mock('@/services/arte-ai/toolChat', async original => ({
  ...await original<typeof import('@/services/arte-ai/toolChat')>(),
  getRunResult: vi.fn()
}));

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getConversation: vi.fn(),
  queryTurnsAround: vi.fn(),
  getInvocationSnapshot: vi.fn()
}));
const scope = {tenantId: 't', workspaceId: 'w'};
const conversation: api.ConversationResponse = {
  conversationId: 'c',
  title: 'title',
  version: 1,
  state: 'ACTIVE',
  chatProfile: null,
  resources: [],
  createdAt: '',
  updatedAt: ''
};
const turn = (n: number): api.ConversationTurnResponse => ({
  turnId: `t${n}`,
  conversationId: 'c',
  sequence: n,
  parentTurnId: null,
  supersedesTurnId: null,
  userMessage: {messageId: `m${n}`, role: 'USER', content: [{text: `问题${n}`}], toolCalls: [], toolCallId: null},
  invocationIds: [],
  selectedInvocationId: null,
  version: 0,
  createdAt: '',
  updatedAt: ''
});
const item: api.ConversationSearchItem = {
  entryKey: 'e',
  conversationId: 'c',
  title: 'stale',
  snippet: {text: '旧片段', highlights: []},
  matchKind: 'MESSAGE',
  canLocateMessage: true,
  turnId: 't50',
  messageId: 'm50',
  invocationId: null,
  candidateOrdinal: null,
  partial: false
};
const page = (start: number, version = 1): api.ConversationTurnsAroundPage => ({
  conversationVersion: version,
  records: Array.from({length: 10}, (_, n) => turn(start + n)),
  earlierCursor: start > 1 ? `before${start}` : null,
  laterCursor: `after${start + 10}`
});
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '0', desc: 'ok', data}});
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.getConversation).mockResolvedValue(reply(conversation));
  vi.mocked(api.queryTurnsAround).mockImplementation(async r => reply(page(r.historyCursor?.startsWith('after') ? Number(r.historyCursor.slice(5)) : r.historyCursor?.startsWith('before') ? Number(r.historyCursor.slice(6)) - 10 : 46)));
});
afterEach(cleanup);
it('retries one authoritative version conflict and never searches for an alternative message', async () => {
  vi.mocked(api.queryTurnsAround).mockRejectedValueOnce(new api.AiApiError(409, {code: '205030'}));
  const found = await locateSearchMessage(scope, item, new AbortController().signal);
  expect(found.messageId).toBe('m50');
  expect(api.getConversation).toHaveBeenCalledTimes(2);
  expect(api.queryTurnsAround).toHaveBeenCalledTimes(2);
  vi.mocked(api.queryTurnsAround).mockRejectedValue(new api.AiApiError(409, {code: '205030'}));
  await expect(locateSearchMessage(scope, item, new AbortController().signal)).rejects.toMatchObject({httpStatus: 409});
  expect(api.queryTurnsAround).toHaveBeenCalledTimes(4);
});
it('uses actual message identity, rejects missing candidates and mismatched snapshot ownership', async () => {
  await expect(locateSearchMessage(scope, {
    ...item,
    messageId: 'same-text-wrong-id'
  }, new AbortController().signal)).rejects.toBeInstanceOf(SearchTargetUnavailable);
  await expect(locateSearchMessage(scope, {
    ...item,
    invocationId: 'missing'
  }, new AbortController().signal)).rejects.toBeInstanceOf(SearchTargetUnavailable);
  const p = page(46);
  p.records[4] = {...turn(50), invocationIds: ['old', 'new'], selectedInvocationId: 'new'};
  vi.mocked(api.queryTurnsAround).mockResolvedValue(reply(p));
  vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
    invocationId: 'old',
    missing: false,
    status: {
      invocationId: 'old',
      conversation: {conversationId: 'foreign', turnId: 't50'}
    } as api.InvocationStatusResponse,
    result: null
  });
  await expect(locateSearchMessage(scope, {
    ...item,
    invocationId: 'old'
  }, new AbortController().signal)).rejects.toBeInstanceOf(SearchTargetUnavailable);
});
it('maps a missing or archived parent to conversation invalidation and does not disguise a network error', async () => {
  vi.mocked(api.getConversation).mockRejectedValueOnce(new api.AiApiError(404, {code: '205016'}));
  await expect(locateSearchMessage(scope, item, new AbortController().signal)).rejects.toMatchObject({kind: 'conversation'});
  vi.mocked(api.getConversation).mockRejectedValueOnce(new TypeError('offline'));
  await expect(locateSearchMessage(scope, item, new AbortController().signal)).rejects.toBeInstanceOf(TypeError);
});
it('keeps located history independent of ordinary history, bounds both directions and rebuilds around the visible anchor after a version change', async () => {
  const seed = await locateSearchMessage(scope, item, new AbortController().signal), denied = vi.fn();
  const h = renderHook(({c}) => useLocatedHistory(seed, c, scope, denied), {initialProps: {c: conversation}});
  for (let n = 0; n < 12; n++) await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.turns).toHaveLength(100);
  expect(h.result.current.turns[0].sequence).toBe(76);
  await act(async () => {
    await h.result.current.load('before');
  });
  expect(h.result.current.turns[0].sequence).toBe(66);
  act(() => h.result.current.anchor('t80'));
  vi.mocked(api.getConversation).mockResolvedValue(reply({...conversation, version: 2}));
  vi.mocked(api.queryTurnsAround).mockImplementation(async r => reply(page(Number(r.turnId?.slice(1)) - 4, 2)));
  h.rerender({c: {...conversation, version: 2}});
  await waitFor(() => expect(h.result.current.turns[0].sequence).toBe(76));
  expect(h.result.current.turns).toHaveLength(10);
  expect(api.queryTurnsAround).toHaveBeenLastCalledWith(expect.objectContaining({
    turnId: 't80',
    expectedVersion: 2
  }), expect.anything());
  expect(denied).not.toHaveBeenCalled();
});
it('does not replay cached location after dismissal and clears the view on explicit denial', async () => {
  const seed = await locateSearchMessage(scope, item, new AbortController().signal), denied = vi.fn();
  const h = renderHook(({s}: {
    s: LocatedHistory
  }) => useLocatedHistory(s, conversation, scope, denied), {initialProps: {s: seed}});
  act(() => h.result.current.dismiss());
  expect(h.result.current.active).toBe(false);
  expect(h.result.current.turns).toEqual([]);
  h.rerender({s: {...seed, id: 'new-navigation'}});
  expect(h.result.current.active).toBe(true);
  vi.mocked(api.queryTurnsAround).mockRejectedValue(new api.AiApiError(403, {}));
  await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.active).toBe(false);
  expect(h.result.current.turns).toEqual([]);
  expect(denied).toHaveBeenCalledOnce();
});

it('locates a typed Run answer without validating its final model child as an ordinary Turn invocation', async () => {
  const root = {kind: 'TOOL_CHAT_RUN' as const, executionId: 'run'};
  const t = {...turn(50), candidates: [root], selectedCandidate: root, selectedInvocationId: 'final-child'};
  vi.mocked(api.queryTurnsAround).mockResolvedValue(reply({...page(46), records: [t]}));
  vi.mocked(getRunResult).mockResolvedValue(reply(toolChatAnswer));
  const found = await locateSearchMessage(scope, {
    ...item,
    invocationId: 'final-child',
    candidate: root,
    candidateOrdinal: 1,
    messageId: 'answer-message'
  }, new AbortController().signal);
  expect(found.candidate).toEqual(root);
  expect(found.views['final-child'].result?.kind).toBe('GENERATION');
  expect(api.getInvocationSnapshot).not.toHaveBeenCalled();
});
it('rejects a Run search hit whose claimed final child differs from the authorized Run answer', async () => {
  const root = {kind: 'TOOL_CHAT_RUN' as const, executionId: 'run'};
  const t = {...turn(50), candidates: [root], selectedCandidate: root, selectedInvocationId: 'final-child'};
  vi.mocked(api.queryTurnsAround).mockResolvedValue(reply({...page(46), records: [t]}));
  vi.mocked(getRunResult).mockResolvedValue(reply(toolChatAnswer));
  await expect(locateSearchMessage(scope, {
    ...item,
    invocationId: 'unrelated-child',
    candidate: root,
    candidateOrdinal: 1,
    messageId: 'answer-message'
  }, new AbortController().signal)).rejects.toBeInstanceOf(SearchTargetUnavailable);
});
