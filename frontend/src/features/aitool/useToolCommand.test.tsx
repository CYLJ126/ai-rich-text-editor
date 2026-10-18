import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import {writeToolBinding} from '@/services/arte-ai/tool';
import {useToolCommand} from './useToolCommand';
import {storageKey} from '@/features/ainew/storage';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/tool', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/tool')>()),
  writeToolBinding: vi.fn(),
}));
const context = {
  userId: 'user',
  scope: {tenantId: 'tenant', workspaceId: 'space'},
};
const request = JSON.stringify({
  scope: context.scope,
  tool: {type: 'tool', id: 'tool', version: 'v1'},
  configuration: {},
});
const receipt = {
  delivery: 'APPLIED',
  outcome: 'APPLIED',
  rejection: 'NONE',
  bindingId: 'default-one',
  binding: {bindingId: 'default-one', version: '1'},
};
const success = {body: {data: receipt}} as Awaited<
  ReturnType<typeof writeToolBinding>
>;

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return {promise, resolve, reject};
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(writeToolBinding).mockReset();
  sessionStorage.clear();
  localStorage.clear();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
describe('个人工具命令恢复', () => {
  it('重复点击只提交一次，网络失败复用原键与输入', async () => {
    const pending = deferred<Awaited<ReturnType<typeof writeToolBinding>>>();
    vi.mocked(writeToolBinding)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValue(success);
    const confirmed = vi.fn();
    const {result} = renderHook(() => useToolCommand(context, confirmed));
    act(() => {
      result.current.start('prepareDefaultBinding', request);
      result.current.start('prepareDefaultBinding', request);
    });
    expect(writeToolBinding).toHaveBeenCalledTimes(1);
    await act(async () => pending.reject(new TypeError('network')));
    expect(result.current.pending).not.toBeNull();
    act(() => result.current.retry());
    await waitFor(() => expect(confirmed).toHaveBeenCalledTimes(1));
    expect(vi.mocked(writeToolBinding).mock.calls[0].slice(0, 3)).toEqual(
      vi.mocked(writeToolBinding).mock.calls[1].slice(0, 3),
    );
    expect(result.current.pending).toBeNull();
  });
  it('卸载中止等待，原空间可恢复，其他空间不能读入', async () => {
    const pending = deferred<Awaited<ReturnType<typeof writeToolBinding>>>();
    vi.mocked(writeToolBinding).mockReturnValue(pending.promise);
    const hook = renderHook(() => useToolCommand(context, vi.fn()));
    act(() => hook.result.current.start('prepareDefaultBinding', request));
    const key = hook.result.current.pending?.key;
    const signal = vi.mocked(writeToolBinding).mock.calls[0][3]?.signal;
    hook.unmount();
    expect(signal?.aborted).toBe(true);
    const other = renderHook(() =>
      useToolCommand(
        {...context, scope: {...context.scope, workspaceId: 'other'}},
        vi.fn(),
      ),
    );
    expect(other.result.current.pending).toBeNull();
    other.unmount();
    const restored = renderHook(() => useToolCommand(context, vi.fn()));
    expect(restored.result.current.pending?.key).toBe(key);
    await act(async () => pending.resolve(success));
    expect(restored.result.current.pending?.key).toBe(key);
  });
  it('明确拒绝后允许修正，幂等冲突保持原命令', async () => {
    vi.mocked(writeToolBinding)
      .mockRejectedValueOnce(new AiApiError(422, {code: '205050'}))
      .mockRejectedValueOnce(new AiApiError(409, {code: '205027'}));
    const {result} = renderHook(() => useToolCommand(context, vi.fn()));
    act(() => result.current.start('prepareDefaultBinding', request));
    await waitFor(() => expect(result.current.busy).toBe(false));
    expect(result.current.pending).toBeNull();
    act(() => result.current.start('prepareDefaultBinding', request));
    await waitFor(() => expect(result.current.busy).toBe(false));
    expect(result.current.pending).not.toBeNull();
  });
  it('200 不完整回执不能误报成功', async () => {
    vi.mocked(writeToolBinding).mockResolvedValue({
      body: {data: {...receipt, outcome: 'INVALID_STATE'}},
    } as typeof success);
    const confirmed = vi.fn();
    const {result} = renderHook(() => useToolCommand(context, confirmed));
    act(() => result.current.start('prepareDefaultBinding', request));
    await waitFor(() => expect(result.current.busy).toBe(false));
    expect(result.current.pending).not.toBeNull();
    expect(confirmed).not.toHaveBeenCalled();
  });
  it('跨空间恢复记录阻止新操作', () => {
    sessionStorage.setItem(
      storageKey(context, 'tool-binding-command'),
      JSON.stringify({
        schemaVersion: 1,
        value: {
          path: 'prepareDefaultBinding',
          key: 'key',
          requestJson: '{"scope":{"tenantId":"other","workspaceId":"space"}}',
        },
      }),
    );
    const {result} = renderHook(() => useToolCommand(context, vi.fn()));
    expect(result.current.blocked).toBe(true);
    act(() => result.current.start('prepareDefaultBinding', request));
    expect(writeToolBinding).not.toHaveBeenCalled();
  });
  it('恢复记录无法保存时不发送操作', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('quota');
      },
    });
    const {result} = renderHook(() => useToolCommand(context, vi.fn()));
    act(() => result.current.start('prepareDefaultBinding', request));
    expect(writeToolBinding).not.toHaveBeenCalled();
    expect(result.current.error).toContain('尚未发送');
  });
  it('凭据变化后迟到成功不确认或清理原账号命令', async () => {
    localStorage.setItem('user_token', 'old');
    const pending = deferred<Awaited<ReturnType<typeof writeToolBinding>>>();
    vi.mocked(writeToolBinding).mockReturnValue(pending.promise);
    const confirmed = vi.fn();
    const {result} = renderHook(() => useToolCommand(context, confirmed));
    act(() => result.current.start('prepareDefaultBinding', request));
    localStorage.setItem('user_token', 'new');
    await act(async () => pending.resolve(success));
    expect(confirmed).not.toHaveBeenCalled();
    expect(result.current.pending).not.toBeNull();
  });
});
