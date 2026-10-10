import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import * as api from '@/services/platform/workspace';
import {useWorkspaceDirectory} from './useWorkspace';
import {AiApiError} from '@/services/arte-ai';
import {writeValue} from '@/features/ainew/storage';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/workspace', async original => ({
  ...(await original<typeof import('@/services/platform/workspace')>()),
  getWorkspace: vi.fn(),
  getWorkspacePreference: vi.fn(),
  listAccessibleWorkspaces: vi.fn(),
  initializePersonalWorkspace: vi.fn(),
  checkWorkspaceAccess: vi.fn(),
  createWorkspace: vi.fn(),
  updateWorkspacePreference: vi.fn(),
}));
const a = {tenantId: 'tenant', workspaceId: 'a'};
const b = {tenantId: 'tenant', workspaceId: 'b'};
const view = (scope = a): api.WorkspaceView => ({scope, name: scope.workspaceId, state: 0, currentRole: 0, version: 1});
const preference: api.WorkspacePreference = {
  defaultScope: a,
  startupScope: null,
  lastScope: null,
  favorites: [],
  version: 0
};
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
let counter = 0;
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  vi.mocked(api.getWorkspace).mockImplementation(async scope => reply(view(scope)));
  vi.mocked(api.getWorkspacePreference).mockResolvedValue(reply(preference));
  vi.mocked(api.listAccessibleWorkspaces).mockResolvedValue({
    httpStatus: 200,
    body: {
      success: true,
      code: '200',
      desc: 'OK',
      records: [{...view(), defaultWorkspace: true}],
      current: 1,
      size: 20,
      total: 21
    }
  });
  vi.mocked(api.checkWorkspaceAccess).mockResolvedValue(reply({allowed: true, reason: 'ALLOWED', checkedAt: 'now'}));
});
afterEach(cleanup);

it('uses the public prefix and numeric view enums without fabricating AI permission', async () => {
  const actual = await vi.importActual<typeof import('@/services/platform/workspace')>('@/services/platform/workspace');
  vi.mocked(request).mockResolvedValue({status: 200, data: reply(view()).body});
  const response = await actual.getWorkspace(a);
  expect(response.body.data.currentRole).toBe(0);
  expect(request).toHaveBeenCalledWith('/arte/workspace/getWorkspace', expect.objectContaining({
    data: {scope: a},
    skipErrorHandler: true
  }));
  await actual.checkWorkspaceAccess({tenantId: 'tenant'}, 'tenant', 'tenant:workspace:create');
  expect(request).toHaveBeenLastCalledWith('/arte/workspace/access/checkWorkspaceAccess', expect.objectContaining({
    data: {
      scope: {tenantId: 'tenant'},
      resourceType: 'tenant',
      action: 'tenant:workspace:create'
    }
  }));
});
it('loads a real default workspace and preserves server pagination total', async () => {
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  expect(hook.result.current.total).toBe(21);
  expect(api.initializePersonalWorkspace).not.toHaveBeenCalled();
});
it('falls back from an inaccessible startup preference to the real default', async () => {
  vi.mocked(api.getWorkspacePreference).mockResolvedValue(reply({...preference, startupScope: b}));
  vi.mocked(api.getWorkspace).mockImplementation(async scope => {
    if (scope.workspaceId === 'b') throw new AiApiError(404, {desc: 'hidden'});
    return reply(view(scope));
  });
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  expect(hook.result.current.warning).toContain('已回到个人默认空间');
});
it('does not let a late directory response overwrite a deliberate selection', async () => {
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  let resolve!: (value: ReturnType<typeof reply<api.WorkspaceView>>) => void;
  vi.mocked(api.getWorkspace).mockImplementation(scope => scope.workspaceId === 'a' ? new Promise(done => {
    resolve = done;
  }) : Promise.resolve(reply(view(scope))));
  let loading!: Promise<void>;
  act(() => {
    loading = hook.result.current.load();
  });
  await waitFor(() => expect(resolve).toBeTypeOf('function'));
  await act(async () => {
    await hook.result.current.select(b);
  });
  await act(async () => {
    resolve(reply(view(a)));
    await loading;
  });
  expect(hook.result.current.current?.scope).toEqual(b);
});
it('restores an uncertain workspace command using the original request and key', async () => {
  const userId = `account-${++counter}`;
  const original = {
    kind: 'create',
    key: 'original-key',
    request: {tenantId: 'tenant', name: 'original', description: 'description'}
  };
  writeValue(`arte.ainew.workspaceOperation.v1.${encodeURIComponent(JSON.stringify([userId]))}`, original);
  vi.mocked(api.createWorkspace).mockRejectedValue(new TypeError('network'));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await act(async () => {
    await hook.result.current.retry();
  });
  expect(api.createWorkspace).toHaveBeenCalledWith(original.request, original.key, expect.anything());
  expect(hook.result.current.pending).toEqual(original);
});
it('shares selection within the same account and tab but never reads a different account cache', async () => {
  const userId = `account-${++counter}`;
  const first = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(first.result.current.current?.scope).toEqual(a));
  await act(async () => {
    await first.result.current.select(b);
  });
  const second = renderHook(() => useWorkspaceDirectory(userId));
  const otherUserId = `account-${++counter}`;
  const other = renderHook(() => useWorkspaceDirectory(otherUserId));
  await waitFor(() => expect(other.result.current.current?.scope).toEqual(a));
  expect(second.result.current.current?.scope).toEqual(b);
});
