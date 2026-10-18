import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {useToolPage} from './useToolPage';
import type {AiHttpResponse, AiResult} from '@/services/arte-ai/types';
import type {ToolPage} from '@/services/arte-ai/tool';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const response = (records: string[], cursor: string | null) =>
  ({
    body: {
      data: {
        records,
        nextCursor: cursor,
        exhausted: cursor === null,
        scanned: 1,
      },
    },
  }) as AiHttpResponse<AiResult<ToolPage<string>>>;
afterEach(cleanup);
describe('工具目录游标与作用域', () => {
  it('空可见页不代表结束', async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce(response([], 'next'))
      .mockResolvedValueOnce(response(['visible'], null));
    const {result} = renderHook(() => useToolPage(load));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.exhausted).toBe(false);
    act(() => result.current.more());
    await waitFor(() => expect(result.current.records).toEqual(['visible']));
    expect(load.mock.calls[1][0]).toBe('next');
  });
  it('条件变化中止旧请求并丢弃迟到响应', async () => {
    let complete!: (value: ReturnType<typeof response>) => void;
    const old = vi.fn().mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    const next = vi.fn().mockResolvedValue(response(['new'], null));
    const {result, rerender} = renderHook(({load}) => useToolPage(load), {
      initialProps: {load: old},
    });
    const signal = old.mock.calls[0][1] as AbortSignal;
    rerender({load: next});
    await waitFor(() => expect(result.current.records).toEqual(['new']));
    expect(signal.aborted).toBe(true);
    await act(async () => complete(response(['old'], null)));
    expect(result.current.records).toEqual(['new']);
  });
  it('游标停滞时明确报错', async () => {
    const load = vi.fn().mockResolvedValue(response([], 'same'));
    const {result} = renderHook(() => useToolPage(load));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.more());
    await waitFor(() => expect(result.current.error).toContain('游标'));
    expect(result.current.records).toEqual([]);
  });
});
