import {afterEach, describe, expect, it, vi} from 'vitest';
import {watchInvocation} from './invocationStream';
import {AiApiError} from './request';

vi.mock('@umijs/max', () => ({getLocale: () => 'zh-CN', request: vi.fn()}));
const query = {
  scope: {tenantId: 'tenant', workspaceId: 'workspace'},
  invocationId: 'invocation',
  afterSequence: 0,
};
const notification = {
  executionId: 'invocation',
  sequence: 1,
  kind: 'ACCEPTED',
};

function stream(chunks: Uint8Array[]) {
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(chunk);
        controller.close();
      },
    }),
    {headers: {'Content-Type': 'text/event-stream'}},
  );
}

const bytes = (text: string) => new TextEncoder().encode(text);
const frame = (sequence: number, kind = 'TERMINAL') =>
  `id: ${sequence}\nevent: invocation\ndata: ${JSON.stringify({...notification, sequence, kind})}\n\n`;
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  localStorage.clear();
});

describe('带认证的 Invocation SSE', () => {
  it('实时文字帧保留 UTF-8 与空白、不携带耐久游标，不干扰事件去重', async () => {
    const delta = {
      executionId: 'invocation',
      attemptId: 'attempt',
      offset: 0,
      text: '你🙂\n ',
    };
    const body =
      frame(1, 'ACCEPTED') +
      `event: text-delta\ndata: ${JSON.stringify(delta)}\n\n` +
      frame(1, 'ACCEPTED') +
      frame(2);
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          stream(Array.from(bytes(body), (value) => new Uint8Array([value]))),
        ),
    );
    const event = vi.fn();
    const text = vi.fn();
    await watchInvocation(query, event, {
      signal: new AbortController().signal,
      onText: text,
    });
    expect(text).toHaveBeenCalledExactlyOnceWith(delta);
    expect(event.mock.calls.map(([value]) => value.sequence)).toEqual([1, 2]);
  });

  it.each([
    {offset: -1},
    {offset: 1.5},
    {offset: 1_000_000},
    {text: ''},
    {text: '字'.repeat(257)},
    {attemptId: ''},
    {executionId: 'other'},
  ])('拒绝非法实时文字帧', async (invalid) => {
    const delta = {
      executionId: 'invocation',
      attemptId: 'attempt',
      offset: 0,
      text: '字',
      ...invalid,
    };
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          stream([
            bytes(`event: text-delta\ndata: ${JSON.stringify(delta)}\n\n`),
          ]),
        ),
    );
    const onText = vi.fn();
    await expect(
      watchInvocation(query, vi.fn(), {
        signal: new AbortController().signal,
        onText,
      }),
    ).rejects.toThrow();
    expect(onText).not.toHaveBeenCalled();
  });

  it('跨字节/CRLF 分块解析、多行 data 和注释心跳，重复序号只通知一次', async () => {
    const text =
      ': 心跳\r\n\r\nid: 1\r\nevent: invocation\r\ndata: {"executionId":"invocation",\r\ndata: "sequence":1,"kind":"ACCEPTED"}\r\n\r\n' +
      frame(1, 'ACCEPTED') +
      frame(2) +
      frame(3, 'BUDGET_CHANGED');
    const encoded = bytes(text);
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        stream(Array.from(encoded, (value) => new Uint8Array([value]))),
      );
    vi.stubGlobal('fetch', fetcher);
    localStorage.setItem('user_token', 'test-token');
    const received = vi.fn();
    const connected = vi.fn();
    await watchInvocation(query, received, {
      signal: new AbortController().signal,
      onConnected: connected,
    });
    expect(received.mock.calls.map(([event]) => event.sequence)).toEqual([
      1, 2, 3,
    ]);
    expect(connected).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      method: 'POST',
      headers: {
        Authorization: 'Bearer test-token',
        'Accept-Language': 'zh-CN',
      },
      body: JSON.stringify(query),
    });
  });

  it('解析文字增量并保持换行空白，重复序号不再追加', async () => {
    const output = {
      ...notification,
      kind: 'OUTPUT',
      text: ' 你好\n<script>文本</script> ',
    };
    const body = `id: 1\nevent: invocation\ndata: ${JSON.stringify(output)}\n\n`;
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(stream([bytes(body + body)])),
    );
    const received = vi.fn();
    await watchInvocation(query, received, {
      signal: new AbortController().signal,
    });
    expect(received).toHaveBeenCalledExactlyOnceWith(output);
  });

  it.each([null, 123, 'x'.repeat(1_000_001)])(
    '拒绝非法或超出服务端总文字上限的增量',
    async (text) => {
      vi.stubGlobal(
        'fetch',
        vi
          .fn()
          .mockResolvedValue(
            stream([
              bytes(
                `id: 1\nevent: invocation\ndata: ${JSON.stringify({...notification, kind: 'OUTPUT', text})}\n\n`,
              ),
            ]),
          ),
      );
      await expect(
        watchInvocation(query, vi.fn(), {
          signal: new AbortController().signal,
        }),
      ).rejects.toThrow();
    },
  );

  it('支持单独 CR 的最终帧且忽略恢复游标之前的通知', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          stream([bytes((frame(1) + frame(2)).replaceAll('\n', '\r'))]),
        ),
    );
    const received = vi.fn();
    await watchInvocation({...query, afterSequence: 1}, received, {
      signal: new AbortController().signal,
    });
    expect(received).toHaveBeenCalledExactlyOnceWith({
      ...notification,
      sequence: 2,
      kind: 'TERMINAL',
    });
  });

  it.each([401, 403, 410, 503])(
    'HTTP %s 保留错误，流错误也保留状态',
    async (status) => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(JSON.stringify({code: 'test', desc: 'rejected'}), {
            status,
          }),
        ),
      );
      await expect(
        watchInvocation(query, vi.fn(), {
          signal: new AbortController().signal,
        }),
      ).rejects.toMatchObject({httpStatus: status, code: 'test'});
      vi.mocked(fetch).mockResolvedValue(
        stream([
          bytes(
            `event: error\ndata: {"httpStatus":${status},"code":"test"}\n\n`,
          ),
        ]),
      );
      await expect(
        watchInvocation(query, vi.fn(), {
          signal: new AbortController().signal,
        }),
      ).rejects.toBeInstanceOf(AiApiError);
    },
  );

  it('拒绝错调用、序号不一致及超大事件帧，释放流读取器', async () => {
    for (const text of [
      frame(1).replace('invocation","sequence', 'foreign","sequence'),
      frame(1).replace('id: 1', 'id: 2'),
      `data: ${'x'.repeat(8 * 1024 * 1024 + 1)}`,
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(stream([bytes(text)])));
      await expect(
        watchInvocation(query, vi.fn(), {
          signal: new AbortController().signal,
        }),
      ).rejects.toThrow();
    }
  });

  it('取消连接会取消底层 reader，不触发业务通知', async () => {
    const cancel = vi.fn();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(new ReadableStream({cancel}), {
          headers: {'Content-Type': 'text/event-stream'},
        }),
      ),
    );
    const controller = new AbortController();
    const connected = vi.fn();
    const promise = watchInvocation(query, vi.fn(), {
      signal: controller.signal,
      onConnected: connected,
    });
    await vi.waitFor(() => expect(connected).toHaveBeenCalledOnce());
    controller.abort();
    await expect(promise).rejects.toMatchObject({name: 'AbortError'});
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('45 秒无心跳主动结束连接以便低频恢复查询', async () => {
    vi.useFakeTimers();
    const cancel = vi.fn();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(new ReadableStream({cancel}), {
          headers: {'Content-Type': 'text/event-stream'},
        }),
      ),
    );
    const promise = watchInvocation(query, vi.fn(), {
      signal: new AbortController().signal,
    });
    const assertion = expect(promise).rejects.toMatchObject({
      name: 'AbortError',
    });
    await vi.advanceTimersByTimeAsync(45_000);
    await assertion;
    expect(cancel).toHaveBeenCalledOnce();
  });
});
