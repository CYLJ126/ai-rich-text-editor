import {getLocale} from '@umijs/max';
import {AiApiError} from './request';
import type {ExecutionEvent, InvocationQuery} from './types';

export type InvocationNotification = Pick<
  ExecutionEvent,
  'executionId' | 'sequence' | 'kind'
>;
const MAX_FRAME_SIZE = 64 * 1024;
const IDLE_TIMEOUT_MS = 45_000;

/** POST SSE 可携带现有 Bearer Token；只接收已提交事件指针，不自动提交或重执行消息。 */
export async function watchInvocation(
  request: InvocationQuery & { afterSequence: number },
  onEvent: (event: InvocationNotification) => void | Promise<void>,
  options: { signal: AbortSignal; onConnected?: () => void },
): Promise<void> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal.addEventListener('abort', abort, {once: true});
  if (options.signal.aborted) controller.abort();
  let idle: ReturnType<typeof setTimeout> | undefined;
  const touch = () => {
    clearTimeout(idle);
    idle = setTimeout(abort, IDLE_TIMEOUT_MS);
  };
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  touch();
  try {
    const token = localStorage.getItem('user_token');
    const response = await fetch('/arte/ai-new/invocation/watchInvocation', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // JSON 也在 Accept 内：建连前的 401/403/410 保持现有错误信封。
        Accept: 'text/event-stream, application/json',
        'Accept-Language': getLocale(),
        ...(token ? {Authorization: `Bearer ${token}`} : {}),
      },
      body: JSON.stringify(request),
      signal: controller.signal,
    });
    if (!response.ok) {
      const body: unknown = await response.json().catch(() => null);
      throw new AiApiError(response.status, body);
    }
    if (
      !response.headers.get('content-type')?.includes('text/event-stream') ||
      !response.body
    ) {
      throw new Error('Invalid AI event stream');
    }
    options.onConnected?.();
    reader = response.body.getReader();
    const cancelReader = () => {
      void reader?.cancel().catch(() => {
      });
    };
    controller.signal.addEventListener('abort', cancelReader, {once: true});
    const decoder = new TextDecoder();
    let buffer = '';
    let lines: string[] = [];
    let frameSize = 0;
    let after = request.afterSequence;
    const consume = async (line: string) => {
      if (line !== '') {
        frameSize += line.length;
        if (frameSize > MAX_FRAME_SIZE)
          throw new Error('AI event frame is too large');
        lines.push(line);
        return;
      }
      let type = 'message';
      let id = '';
      const data: string[] = [];
      for (const field of lines) {
        const colon = field.indexOf(':');
        const name = colon < 0 ? field : field.slice(0, colon);
        let value = colon < 0 ? '' : field.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (name === 'event') type = value;
        if (name === 'id') id = value;
        if (name === 'data') data.push(value);
      }
      lines = [];
      frameSize = 0;
      if (!data.length) return; // 心跳注释不触发状态查询。
      const payload: unknown = JSON.parse(data.join('\n'));
      if (type === 'error') {
        const body = payload as { httpStatus?: number };
        throw new AiApiError(body?.httpStatus ?? 503, payload);
      }
      if (type !== 'invocation') return;
      const event = payload as InvocationNotification;
      if (
        !event ||
        event.executionId !== request.invocationId ||
        !Number.isSafeInteger(event.sequence) ||
        event.sequence < 1 ||
        !/^\d+$/.test(id) ||
        Number(id) !== event.sequence ||
        ![
          'ACCEPTED',
          'STARTED',
          'OUTPUT',
          'CHECKPOINT',
          'CONTROL',
          'TERMINAL',
          'BUDGET_CHANGED',
        ].includes(event.kind)
      ) {
        throw new Error('Invalid AI invocation notification');
      }
      if (event.sequence <= after) return;
      await onEvent(event);
      after = event.sequence;
    };
    while (!controller.signal.aborted) {
      const chunk = await reader.read();
      if (chunk.done) {
        buffer += decoder.decode();
        if (buffer.endsWith('\r')) buffer += '\n';
      } else {
        touch();
        buffer += decoder.decode(chunk.value, {stream: true});
      }
      // 支持跨 chunk 的 UTF-8、CRLF，以及 SSE 允许的单独 CR。
      while (true) {
        const index = buffer.search(/[\r\n]/);
        if (
          index < 0 ||
          (buffer[index] === '\r' && index === buffer.length - 1)
        )
          break;
        const line = buffer.slice(0, index);
        const width =
          buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1;
        buffer = buffer.slice(index + width);
        await consume(line);
      }
      if (buffer.length + frameSize > MAX_FRAME_SIZE)
        throw new Error('AI event frame is too large');
      if (chunk.done) break;
    }
    if (controller.signal.aborted)
      throw new DOMException('Aborted', 'AbortError');
  } finally {
    clearTimeout(idle);
    options.signal.removeEventListener('abort', abort);
    controller.abort();
    await reader?.cancel().catch(() => {
    });
  }
}
