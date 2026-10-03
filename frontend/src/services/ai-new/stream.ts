import {z} from 'zod';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import type {WorkspaceSelection} from '@/types/ai-new/conversation';
import {AiNewApiError, normalizeApiError} from './request';

const errorSchema = z.object({
  code: z.string(), failureStage: z.string(), retryable: z.boolean(),
  sideEffectStatus: z.enum(['NONE', 'OCCURRED', 'UNKNOWN']), resultCertainty: z.enum(['CONFIRMED', 'UNKNOWN'])
});
export const streamEventSchema = z.object({
  executionId: z.string().min(1),
  sequence: z.number().int().nonnegative().refine(Number.isSafeInteger),
  status: z.enum(['ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED', 'TIMED_OUT', 'OUTCOME_UNKNOWN', 'CANCELLED']),
  textDelta: z.string().nullable(),
  result: z.object({output: z.array(z.object({text: z.string()})).min(1)}).nullable(),
  error: errorSchema.nullable(),
}).refine(event => (event.textDelta === null || event.status === 'RUNNING') && (event.status !== 'SUCCEEDED' || event.result !== null));
export type ChatStreamEvent = z.infer<typeof streamEventSchema>;

/** 只在 HTTP 流完全收到一帧后推进游标；重复帧不重复拼接文本。 */
export class ChatSseDecoder {
  private buffer = '';
  private event = '';
  private id = '';
  private data: string[] = [];

  constructor(private readonly receive: (event: string, id: string, data: string) => void) {
  }

  push(text: string) {
    this.buffer += text;
    if (this.buffer.length > 2 * 1024 * 1024) throw new AiNewApiError(502, null);
    let end: number;
    while ((end = this.buffer.indexOf('\n')) !== -1) {
      const line = this.buffer.slice(0, end).replace(/\r$/, '');
      this.buffer = this.buffer.slice(end + 1);
      if (!line) {
        if (this.data.length) this.receive(this.event || 'message', this.id, this.data.join('\n'));
        this.event = '';
        this.id = '';
        this.data = [];
      } else if (!line.startsWith(':')) {
        const colon = line.indexOf(':');
        const key = colon === -1 ? line : line.slice(0, colon);
        const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '');
        if (key === 'data') this.data.push(value);
        else if (key === 'event') this.event = value;
        else if (key === 'id') this.id = value;
        if (this.data.reduce((length, item) => length + item.length, 0) > 2 * 1024 * 1024) throw new AiNewApiError(502, null);
      }
    }
  }
}

export async function observeChatEvents(scope: WorkspaceSelection, conversationId: string, turnId: string, executionId: string,
                                        after: number, signal: AbortSignal, receive: (event: ChatStreamEvent) => void, connected?: (value: boolean) => void) {
  let cursor = after;
  let terminal = false;
  const query = () => new URLSearchParams({
    tenantId: scope.tenantId,
    workspaceId: scope.workspaceId,
    after: String(cursor)
  });
  for (let attempt = 0; !signal.aborted && !terminal && attempt < 4; attempt++) {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      const token = localStorage.getItem('user_token');
      const response = await fetch(`/arte/api/ai-new/conversations/${encodeURIComponent(conversationId)}/turns/${encodeURIComponent(turnId)}/events?${query()}`, {
        signal,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {Accept: 'text/event-stream', ...(token ? {Authorization: `Bearer ${token}`} : {})},
      });
      if (!response.ok) {
        const data = await response.json().catch(() => null);
        const facts = errorSchema.safeParse(data);
        throw new AiNewApiError(response.status, facts.success ? facts.data : null);
      }
      if (!response.headers.get('content-type')?.startsWith('text/event-stream') || !response.body) throw new AiNewApiError(502, null);
      connected?.(true);
      const decoder = new ChatSseDecoder((name, id, data) => {
        if (name === 'failure') {
          const facts = errorSchema.safeParse(JSON.parse(data));
          throw new AiNewApiError(facts.success && facts.data.code.endsWith('.unauthorized') ? 403 : 503, facts.success ? facts.data : null);
        }
        if (name !== 'model') return;
        const parsed = streamEventSchema.safeParse(JSON.parse(data));
        if (!parsed.success || parsed.data.executionId !== executionId || id !== String(parsed.data.sequence)) throw new AiNewApiError(502, null);
        if (parsed.data.sequence <= cursor) return;
        receive(parsed.data);
        cursor = parsed.data.sequence;
        terminal = !['ACCEPTED', 'RUNNING'].includes(parsed.data.status);
      });
      const utf8 = new TextDecoder('utf-8', {fatal: true});
      reader = response.body.getReader();
      while (!signal.aborted && !terminal) {
        const next = await reader.read();
        if (next.done) {
          decoder.push(utf8.decode());
          break;
        }
        decoder.push(utf8.decode(next.value, {stream: true}));
      }
    } catch (error) {
      if (signal.aborted) return;
      const failure = normalizeApiError(error);
      if ([401, 403, 404].includes(failure.status) || attempt === 3) throw failure;
    } finally {
      connected?.(false);
      await reader?.cancel().catch(() => {
      });
    }
    if (!signal.aborted && !terminal) await new Promise<void>(resolve => {
      const timer = setTimeout(done, Math.min(1000 * 2 ** attempt, 5000));

      function done() {
        clearTimeout(timer);
        signal.removeEventListener('abort', done);
        resolve();
      }

      signal.addEventListener('abort', done, {once: true});
    });
  }
}

export function applyChatStreamEvent(item: ChatTurnResult, event: ChatStreamEvent): ChatTurnResult {
  if (!item.execution || item.execution.executionId !== event.executionId || !['ACCEPTED', 'RUNNING'].includes(item.execution.status)) return item;
  const previous = item.execution.partialSequence ?? -1;
  if (event.sequence <= previous) return item;
  return {
    ...item, execution: {
      ...item.execution, status: event.status,
      partialText: (item.execution.partialText ?? '') + (event.textDelta ?? ''), partialSequence: event.sequence,
      result: event.result, error: event.error
    }
  };
}
