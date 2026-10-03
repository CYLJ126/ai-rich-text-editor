import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {applyChatStreamEvent, ChatSseDecoder, observeChatEvents, type ChatStreamEvent} from './stream';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import {AiNewApiError} from './request';

const scope = {tenantId: 'tenant', workspaceId: 'space'};
const event = (sequence: number, textDelta: string | null, status: ChatStreamEvent['status'] = 'RUNNING'): ChatStreamEvent => ({
  executionId: 'execution', sequence, status, textDelta, error: null,
  result: status === 'SUCCEEDED' ? {output: [{text: '中😀完成'}]} : null,
});
const frame = (item: ChatStreamEvent) => `id: ${item.sequence}\nevent: model\ndata: ${JSON.stringify(item)}\n\n`;
const response = (text: string) => new Response(new ReadableStream<Uint8Array>({
  start(controller) {
    const bytes = new TextEncoder().encode(text);
    for (let i = 0; i < bytes.length; i++) controller.enqueue(bytes.slice(i, i + 1));
    controller.close();
  }
}), {headers: {'Content-Type': 'text/event-stream'}});
const item = (): ChatTurnResult => ({
  turn: {
    turnId: 'turn',
    conversationId: 'conversation',
    sequence: 1,
    kind: 'MESSAGE',
    status: 'ACCEPTED',
    regeneratesTurnId: null,
    input: [{role: 'USER', parts: [{text: 'question'}]}],
    idempotencyKey: {key: 'key'},
    rejectionError: null,
    createdAt: '2026-10-03T00:00:00Z'
  },
  execution: {
    executionId: 'execution',
    status: 'RUNNING',
    result: null,
    error: null,
    partialText: '',
    partialSequence: -1
  },
});
beforeEach(() => {
  localStorage.clear();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('durable SSE observation', () => {
  it('decodes split lines, multiple data fields and comments', () => {
    const receive = vi.fn();
    const decoder = new ChatSseDecoder(receive);
    const text = ': keep-alive\r\n\r\nid: 2\r\nevent: model\r\ndata: {"text":\r\ndata: "hello"}\r\n\r\n';
    for (const letter of text) decoder.push(letter);
    expect(receive).toHaveBeenCalledOnce();
    expect(receive).toHaveBeenCalledWith('model', '2', '{"text":\n"hello"}');
  });
  it('reconnects using the last complete cursor and does not duplicate Unicode deltas', async () => {
    vi.useFakeTimers();
    localStorage.setItem('user_token', 'test-token');
    const fetcher = vi.fn().mockResolvedValueOnce(response(frame(event(2, '中😀')) + frame(event(2, '中😀'))))
      .mockResolvedValueOnce(response(frame(event(3, '完成')) + frame(event(4, null, 'SUCCEEDED'))));
    vi.stubGlobal('fetch', fetcher);
    const receive = vi.fn();
    const controller = new AbortController();
    const observed = observeChatEvents(scope, 'conversation', 'turn', 'execution', -1, controller.signal, receive);
    await vi.advanceTimersByTimeAsync(1100);
    await observed;
    expect(receive.mock.calls.map(call => call[0].sequence)).toEqual([2, 3, 4]);
    expect(fetcher.mock.calls[1][0]).toContain('after=2');
    expect(fetcher.mock.calls[0][1].headers.Authorization).toBe('Bearer test-token');
    expect(fetcher.mock.calls[0][1].credentials).toBe('same-origin');
    const final = receive.mock.calls.reduce((previous, call) => applyChatStreamEvent(previous, call[0]), item());
    expect(final.execution?.partialText).toBe('中😀完成');
    expect(final.execution?.status).toBe('SUCCEEDED');
  });
  it('stops on authorization errors without retrying or exposing server text', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({message: 'private server detail'}), {status: 403}));
    vi.stubGlobal('fetch', fetcher);
    const promise = observeChatEvents(scope, 'c', 't', 'execution', -1, new AbortController().signal, vi.fn());
    await expect(promise).rejects.toMatchObject({status: 403, facts: null});
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it('abort does not reconnect or submit a new model request', async () => {
    const controller = new AbortController();
    const receive = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async () => {
      controller.abort();
      throw new DOMException('aborted', 'AbortError');
    }));
    await observeChatEvents(scope, 'c', 't', 'execution', -1, controller.signal, receive);
    expect(fetch).toHaveBeenCalledOnce();
    expect(receive).not.toHaveBeenCalled();
  });
  it('ignores duplicates, foreign executions and late updates after terminal state', () => {
    const first = applyChatStreamEvent(item(), event(2, 'partial'));
    expect(applyChatStreamEvent(first, event(2, 'partial'))).toBe(first);
    expect(applyChatStreamEvent(first, {...event(3, 'foreign'), executionId: 'foreign'})).toBe(first);
    const completed = applyChatStreamEvent(first, event(4, null, 'SUCCEEDED'));
    expect(applyChatStreamEvent(completed, event(5, 'late'))).toBe(completed);
  });
  it('bounds incomplete frames rather than accumulating arbitrary data', () => {
    const decoder = new ChatSseDecoder(vi.fn());
    expect(() => decoder.push('x'.repeat(2 * 1024 * 1024 + 1))).toThrow(AiNewApiError);
  });
});
