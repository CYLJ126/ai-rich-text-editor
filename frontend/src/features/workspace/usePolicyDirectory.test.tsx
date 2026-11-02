import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import {browseAccessibleWorkspaces, type WorkspaceBrowsePage} from '@/services/platform/workspace';
import {usePolicyDirectory} from './usePolicyDirectory';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/workspace', async original => ({
  ...(await original<typeof import('@/services/platform/workspace')>()), browseAccessibleWorkspaces: vi.fn(),
}));
const reply = (data: WorkspaceBrowsePage) => ({
  httpStatus: 200,
  body: {data}
}) as Awaited<ReturnType<typeof browseAccessibleWorkspaces>>;
const record = {
  scope: {tenantId: 'tenant-b', workspaceId: 'space-b'}, name: '空间 B', version: 0,
  state: 0 as const, currentRole: 0 as const, defaultWorkspace: false
};
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

it('browses an unfiltered directory and continues beyond an empty partial page', async () => {
  vi.mocked(browseAccessibleWorkspaces).mockResolvedValueOnce(reply({
    records: [],
    nextCursor: 'next',
    exhausted: false
  }))
    .mockResolvedValueOnce(reply({records: [record], nextCursor: null, exhausted: true}));
  const {result} = renderHook(() => usePolicyDirectory('alice'));
  await waitFor(() => expect(result.current.hasMore).toBe(true));
  expect(result.current.records).toEqual([]);
  await act(async () => result.current.more());
  expect(result.current.records).toEqual([record]);
  expect(result.current.exhausted).toBe(true);
  expect(browseAccessibleWorkspaces).toHaveBeenNthCalledWith(1, null, '', expect.any(Object));
  expect(browseAccessibleWorkspaces).toHaveBeenNthCalledWith(2, 'next', '', expect.any(Object));
});

it('requires a fresh directory after cursor expiry and retains known choices until refresh', async () => {
  vi.mocked(browseAccessibleWorkspaces).mockResolvedValueOnce(reply({
    records: [record],
    nextCursor: 'next',
    exhausted: false
  }))
    .mockRejectedValueOnce(new AiApiError(410, {code: 'PLATFORM_CURSOR_EXPIRED'}))
    .mockResolvedValueOnce(reply({records: [], nextCursor: null, exhausted: true}));
  const {result} = renderHook(() => usePolicyDirectory('alice'));
  await waitFor(() => expect(result.current.hasMore).toBe(true));
  await act(async () => result.current.more());
  expect(result.current.expired).toBe(true);
  expect(result.current.hasMore).toBe(false);
  expect(result.current.records).toEqual([record]);
  await act(async () => result.current.reload());
  expect(result.current.records).toEqual([]);
  expect(result.current.expired).toBe(false);
  expect(browseAccessibleWorkspaces).toHaveBeenLastCalledWith(null, '', expect.any(Object));
});

it('ignores an old account response after switching accounts', async () => {
  let finish!: (response: Awaited<ReturnType<typeof browseAccessibleWorkspaces>>) => void;
  vi.mocked(browseAccessibleWorkspaces).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }))
    .mockResolvedValueOnce(reply({records: [], nextCursor: null, exhausted: true}));
  const {result, rerender} = renderHook(({account}) => usePolicyDirectory(account), {initialProps: {account: 'alice'}});
  const signal = vi.mocked(browseAccessibleWorkspaces).mock.calls[0][2]?.signal;
  rerender({account: 'bob'});
  await waitFor(() => expect(result.current.exhausted).toBe(true));
  expect(signal?.aborted).toBe(true);
  await act(async () => finish(reply({records: [record], nextCursor: null, exhausted: true})));
  expect(result.current.records).toEqual([]);
});

it('reports a directory failure instead of presenting it as an empty completed list', async () => {
  vi.mocked(browseAccessibleWorkspaces).mockRejectedValue(new TypeError('network unavailable'));
  const {result} = renderHook(() => usePolicyDirectory('alice'));
  await waitFor(() => expect(result.current.error).toBe('network unavailable'));
  expect(result.current.exhausted).toBe(false);
});

it('does not request another page after the directory is exhausted', async () => {
  vi.mocked(browseAccessibleWorkspaces).mockResolvedValue(reply({
    records: [record],
    nextCursor: null,
    exhausted: true
  }));
  const {result} = renderHook(() => usePolicyDirectory('alice'));
  await waitFor(() => expect(result.current.exhausted).toBe(true));
  await act(async () => result.current.more());
  expect(browseAccessibleWorkspaces).toHaveBeenCalledOnce();
});
