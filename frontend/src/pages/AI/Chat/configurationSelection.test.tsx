import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {discoverChatOptions, getBudget} from '@/services/arte-ai';
import {useConfigurationDiscovery, useSelectedBudget,} from './useConfigurationSelection';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  discoverChatOptions: vi.fn(),
  getBudget: vi.fn(),
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const account = {
  budgetRef: 'a',
  currency: 'CNY',
  limit: '100',
  held: '0',
  charged: '0',
  available: '0.000000000000000001',
  rateVersion: {type: 'rate' as const, id: 'rate', version: 'v1'},
  version: 0,
};

function reply<T>(data: T) {
  return {
    httpStatus: 200,
    body: {success: true as const, code: '200', desc: 'OK', data},
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return {promise, resolve, reject};
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('配置与预算请求隔离', () => {
  it('清除或卸载时取消配置发现，不采纳迟到失败或结果', async () => {
    const pending = deferred<Awaited<ReturnType<typeof discoverChatOptions>>>();
    vi.mocked(discoverChatOptions).mockReturnValue(pending.promise);
    const hook = renderHook(useConfigurationDiscovery);
    act(() => {
      void hook.result.current.load(scope);
    });
    const signal = vi.mocked(discoverChatOptions).mock.calls[0][1]?.signal;
    act(() => hook.result.current.clear());
    expect(signal?.aborted).toBe(true);
    await act(async () => pending.resolve(reply({options: []})));
    expect(hook.result.current.scope).toBeNull();
    expect(hook.result.current.error).toBeNull();
    act(() => {
      void hook.result.current.load(scope);
    });
    const next = vi.mocked(discoverChatOptions).mock.calls[1][1]?.signal;
    hook.unmount();
    expect(next?.aborted).toBe(true);
  });

  it('连续刷新同一空间时只采纳最后一次发现，迟到失败不覆盖空列表', async () => {
    const first = deferred<Awaited<ReturnType<typeof discoverChatOptions>>>();
    vi.mocked(discoverChatOptions).mockReturnValueOnce(first.promise).mockResolvedValueOnce(reply({options: []}));
    const hook = renderHook(useConfigurationDiscovery);
    act(() => {
      void hook.result.current.load(scope);
    });
    const signal = vi.mocked(discoverChatOptions).mock.calls[0][1]?.signal;
    await act(async () => {
      await hook.result.current.load(scope);
    });
    expect(signal?.aborted).toBe(true);
    await act(async () => first.reject(new Error('stale discovery failure')));
    expect(hook.result.current.scope).toEqual(scope);
    expect(hook.result.current.options).toEqual([]);
    expect(hook.result.current.error).toBeNull();
    expect(hook.result.current.loading).toBe(false);
  });

  it('切换预算马上隐藏旧余额，迟到的旧结果不覆盖新账户', async () => {
    const pending = deferred<Awaited<ReturnType<typeof getBudget>>>();
    vi.mocked(getBudget)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(
        reply({...account, budgetRef: 'b', available: '-1'}),
      );
    const hook = renderHook(
      ({budgetRef}) => useSelectedBudget(scope, budgetRef),
      {initialProps: {budgetRef: 'a'}},
    );
    const signal = vi.mocked(getBudget).mock.calls[0][1]?.signal;
    hook.rerender({budgetRef: 'b'});
    expect(signal?.aborted).toBe(true);
    await waitFor(() =>
      expect(hook.result.current.account?.budgetRef).toBe('b'),
    );
    await act(async () => pending.resolve(reply(account)));
    expect(hook.result.current.account?.budgetRef).toBe('b');
    expect(hook.result.current.account?.available).toBe('-1');
    hook.rerender({budgetRef: ''});
    expect(hook.result.current.account).toBeNull();
  });

  it('相同预算引用在不同空间分别读取，失败不沿用旧余额，可显式刷新', async () => {
    vi.mocked(getBudget)
      .mockResolvedValueOnce(reply(account))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(reply(account));
    const hook = renderHook(
      ({workspaceId}) => useSelectedBudget({...scope, workspaceId}, 'a'),
      {initialProps: {workspaceId: 'space'}},
    );
    await waitFor(() =>
      expect(hook.result.current.account?.available).toBe(account.available),
    );
    hook.rerender({workspaceId: 'next'});
    await waitFor(() =>
      expect(hook.result.current.error).toBeInstanceOf(Error),
    );
    expect(hook.result.current.account).toBeNull();
    act(() => hook.result.current.refresh());
    await waitFor(() =>
      expect(hook.result.current.account?.available).toBe(account.available),
    );
    expect(vi.mocked(getBudget).mock.calls[2][0]).toEqual({
      scope: {...scope, workspaceId: 'next'},
      budgetRef: 'a',
    });
  });
});
