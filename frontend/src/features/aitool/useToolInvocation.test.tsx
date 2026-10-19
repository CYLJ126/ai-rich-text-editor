import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import {getInvocationResult, getInvocationStatus} from '@/services/arte-ai';
import {
  cancelToolInvocation,
  confirmedToolRequest,
  invokeTool,
  toolInvocationRequest,
} from '@/services/arte-ai/toolInvocation';
import {storageKey, writeValue} from '@/features/ainew/storage';
import {useToolInvocation} from './useToolInvocation';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getInvocationResult: vi.fn(),
  getInvocationStatus: vi.fn(),
}));
vi.mock('@/services/arte-ai/toolInvocation', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/toolInvocation')>()),
  invokeTool: vi.fn(),
  cancelToolInvocation: vi.fn(),
}));
const context = {
  userId: 'user',
  scope: {tenantId: 'tenant', workspaceId: 'space'},
};
const requestJson = confirmedToolRequest(
  toolInvocationRequest(context.scope, 'binding', '{}', 30),
  'a'.repeat(64),
);
const response = <T, >(data: T) => ({body: {data}}) as never;
const accepted = response({
  executionId: 'original',
  kind: 'INVOCATION',
  acceptedAt: '2026-10-19T00:00:00Z',
});
const status = (state = 'SUCCEEDED', resultAvailable = false) =>
  response({
    invocationId: 'original',
    kind: 'TOOL',
    state,
    resultAvailable,
    budgetState: 'NOT_RESERVED',
  });
const saved = () =>
  JSON.parse(
    sessionStorage.getItem(storageKey(context, 'tool-invocation')) ?? 'null',
  ).value;
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(invokeTool).mockReset();
  vi.mocked(getInvocationResult).mockReset();
  vi.mocked(getInvocationStatus).mockReset().mockResolvedValue(status());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('独立 TOOL 原调用恢复', () => {
  it('先落盘再发送，重复点击不创建第二条调用', async () => {
    vi.mocked(invokeTool).mockImplementation(() => {
      expect(saved().requestJson).toBe(requestJson);
      return Promise.resolve(accepted);
    });
    const {result} = renderHook(() => useToolInvocation(context));
    act(() => {
      result.current.start(requestJson);
      result.current.start(requestJson);
    });
    await waitFor(() => expect(result.current.status?.state).toBe('SUCCEEDED'));
    expect(invokeTool).toHaveBeenCalledTimes(1);
    expect(saved().acceptedId).toBe('original');
  });
  it('网络失败及刷新后由用户用原键确认，不自动重新提交', async () => {
    vi.mocked(invokeTool)
      .mockRejectedValueOnce(new TypeError('network'))
      .mockResolvedValue(accepted);
    const first = renderHook(() => useToolInvocation(context));
    act(() => first.result.current.start(requestJson));
    await waitFor(() => expect(first.result.current.error).toBeTruthy());
    const original = saved();
    first.unmount();
    const second = renderHook(() => useToolInvocation(context));
    expect(invokeTool).toHaveBeenCalledTimes(1);
    act(() => second.result.current.retry());
    await waitFor(() =>
      expect(second.result.current.record?.acceptedId).toBe('original'),
    );
    expect(vi.mocked(invokeTool).mock.calls[1].slice(0, 2)).toEqual([
      original.requestJson,
      original.key,
    ]);
  });
  it('恢复原调用时被撤权不清除原命令，不允许换键再提交', async () => {
    writeValue(storageKey(context, 'tool-invocation'), {
      key: 'original-key',
      requestJson,
    });
    vi.mocked(invokeTool).mockRejectedValue(new AiApiError(403, {}));
    const {result} = renderHook(() => useToolInvocation(context));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.record?.key).toBe('original-key');
    act(() => result.current.start(requestJson));
    expect(invokeTool).toHaveBeenCalledTimes(1);
  });
  it('已受理记录刷新只查询原 ID，按原文展示精确结果', async () => {
    writeValue(storageKey(context, 'tool-invocation'), {
      key: 'key',
      requestJson,
      acceptedId: 'original',
    });
    vi.mocked(getInvocationStatus).mockResolvedValue(status('SUCCEEDED', true));
    vi.mocked(getInvocationResult).mockResolvedValue(
      response({
        invocationId: 'original',
        kind: 'TOOL',
        result: {
          value: {
            callId: 'call',
            outcome: 'SUCCEEDED',
            sideEffect: 'NONE',
            sources: [],
            artifacts: [],
          },
        },
        outputJson:
          '{"large":9007199254740993,"precise":0.1234567890123456789}',
      }),
    );
    const {result} = renderHook(() => useToolInvocation(context));
    await waitFor(() =>
      expect(result.current.result?.outputJson).toContain('9007199254740993'),
    );
    expect(invokeTool).not.toHaveBeenCalled();
  });
  it('停止回执不会变成最终状态，重复确认停止复用原控制键', async () => {
    writeValue(storageKey(context, 'tool-invocation'), {
      key: 'key',
      requestJson,
      acceptedId: 'original',
    });
    vi.mocked(getInvocationStatus).mockResolvedValue(status('RUNNING'));
    vi.mocked(cancelToolInvocation).mockResolvedValue(
      response({outcome: 'ACCEPTED'}),
    );
    const {result} = renderHook(() => useToolInvocation(context));
    await waitFor(() => expect(result.current.status?.state).toBe('RUNNING'));
    act(() => result.current.cancel());
    await waitFor(() =>
      expect(result.current.cancelReceipt).toContain('正在确认'),
    );
    await waitFor(() => expect(result.current.busy).toBe(false));
    act(() => result.current.cancel());
    await waitFor(() => expect(cancelToolInvocation).toHaveBeenCalledTimes(2));
    expect(vi.mocked(cancelToolInvocation).mock.calls[0][2]).toBe(
      vi.mocked(cancelToolInvocation).mock.calls[1][2],
    );
    expect(result.current.status?.state).not.toBe('CANCELLED');
  });
  it('UNKNOWN 不能一键清除后自动或盲目重试', async () => {
    writeValue(storageKey(context, 'tool-invocation'), {
      key: 'key',
      requestJson,
      acceptedId: 'original',
    });
    vi.mocked(getInvocationStatus).mockResolvedValue(status('UNKNOWN'));
    const {result} = renderHook(() => useToolInvocation(context));
    await waitFor(() => expect(result.current.status?.state).toBe('UNKNOWN'));
    act(() => {
      result.current.clear();
      result.current.start(requestJson);
    });
    expect(result.current.record?.acceptedId).toBe('original');
    expect(invokeTool).not.toHaveBeenCalled();
  });
  it('损坏存储阻止新调用，账号和空间恢复互相隔离', () => {
    sessionStorage.setItem(storageKey(context, 'tool-invocation'), 'bad');
    const first = renderHook(() => useToolInvocation(context));
    expect(first.result.current.blocked).toBe(true);
    act(() => first.result.current.start(requestJson));
    expect(invokeTool).not.toHaveBeenCalled();
    const second = renderHook(() =>
      useToolInvocation({...context, userId: 'other'}),
    );
    expect(second.result.current.record).toBeNull();
    expect(second.result.current.blocked).toBe(false);
  });
  it('历史查询不会提交，读取不到也可结束历史查询', async () => {
    vi.mocked(getInvocationStatus).mockRejectedValue(new AiApiError(404, {}));
    const {result} = renderHook(() => useToolInvocation(context));
    act(() => result.current.open('original'));
    await waitFor(() => expect(result.current.readError).toBeTruthy());
    expect(invokeTool).not.toHaveBeenCalled();
    act(() => result.current.clear());
    expect(result.current.record).toBeNull();
  });
  it('UNKNOWN 经明确确认可结束查看，原编号保留且不会重发', async () => {
    writeValue(storageKey(context, 'tool-invocation'), {
      key: 'key',
      requestJson,
      acceptedId: 'original',
    });
    vi.mocked(getInvocationStatus).mockResolvedValue(status('UNKNOWN'));
    const first = renderHook(() => useToolInvocation(context));
    await waitFor(() =>
      expect(first.result.current.status?.state).toBe('UNKNOWN'),
    );
    act(() => first.result.current.clear(true));
    expect(first.result.current.record).toBeNull();
    expect(first.result.current.history).toEqual(['original']);
    expect(invokeTool).not.toHaveBeenCalled();
    first.unmount();
    const restored = renderHook(() => useToolInvocation(context));
    expect(restored.result.current.history).toEqual(['original']);
  });
  it('隐藏页面不轮询，重新可见只查询原编号', async () => {
    writeValue(storageKey(context, 'tool-invocation'), {
      key: 'key',
      requestJson,
      acceptedId: 'original',
    });
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    const {result} = renderHook(() => useToolInvocation(context));
    expect(getInvocationStatus).not.toHaveBeenCalled();
    hidden.mockReturnValue(false);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    await waitFor(() => expect(result.current.status?.state).toBe('SUCCEEDED'));
    expect(getInvocationStatus).toHaveBeenCalledTimes(1);
    expect(invokeTool).not.toHaveBeenCalled();
  });
});
