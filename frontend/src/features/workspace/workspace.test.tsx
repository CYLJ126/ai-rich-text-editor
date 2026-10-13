import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import * as api from '@/services/platform/workspace';
import {registerWorkspaceGuard, useCurrentWorkspace, useWorkspaceDirectory, workspaceStorageKey} from './useWorkspace';
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
  renameWorkspace: vi.fn(),
  getWorkspaceCommandReceipt: vi.fn(),
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
const receipt = <T, >(value: T) => reply({
  operationId: 'operation', status: 1 as const, delivery: 0 as const, value,
  resultVersion: 1, acceptedAt: '2026-10-11T01:00:00Z', completedAt: '2026-10-11T01:00:00Z'
});
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
  vi.mocked(api.updateWorkspacePreference).mockImplementation(async request => receipt({
    ...preference,
    startupScope: request.startupScope,
    lastScope: request.lastScope,
    favorites: request.favorites,
    version: request.expectedPreferenceVersion + 1,
  }));
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
  vi.mocked(request).mockResolvedValue({
    status: 200,
    data: reply({allowed: true, reason: 'ALLOWED', checkedAt: 'now'}).body
  });
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

it('shares directory, preference and in-flight startup reads between header and module consumers', async () => {
  const userId = `account-${++counter}`;
  const first = renderHook(() => useWorkspaceDirectory(userId));
  const second = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(first.result.current.current?.scope).toEqual(a));
  expect(second.result.current.records).toEqual(first.result.current.records);
  expect(second.result.current.preference).toEqual(first.result.current.preference);
  expect(api.getWorkspacePreference).toHaveBeenCalledTimes(1);
  expect(api.listAccessibleWorkspaces).toHaveBeenCalledTimes(1);
});
it('restores a valid tab selection before server startup and last-access preferences', async () => {
  const userId = `account-${++counter}`;
  writeValue(workspaceStorageKey(userId, 'currentWorkspace'), b);
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(b));
  expect(api.getWorkspace).toHaveBeenCalledWith(b, expect.anything());
});
it('does not switch this tab when another tab changes navigation preferences', async () => {
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  act(() => window.dispatchEvent(new StorageEvent('storage', {
    key: workspaceStorageKey(userId, 'currentWorkspace'),
    newValue: JSON.stringify(b)
  })));
  expect(hook.result.current.current?.scope).toEqual(a);
  expect(api.getWorkspace).toHaveBeenCalledTimes(1);
});
it('initializes a first-time account only on the authoritative missing-preference response', async () => {
  const userId = `account-${++counter}`;
  vi.mocked(api.getWorkspacePreference).mockRejectedValueOnce(new AiApiError(404, {code: 'PLATFORM_NOT_FOUND'}));
  vi.mocked(api.initializePersonalWorkspace).mockResolvedValue(receipt({
    tenant: {id: 'tenant', name: '个人租户', type: 0, state: 0, currentRole: 0, version: 0},
    workspace: view(),
    preference,
  }));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  expect(api.initializePersonalWorkspace).toHaveBeenCalledTimes(1);
  expect(api.initializePersonalWorkspace).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({signal: expect.any(AbortSignal)}));
  expect(hook.result.current.personalTenant?.name).toBe('个人租户');
});
it.each([401, 403, 503])('does not initialize a new space on HTTP %s or retain an unauthorized selection', async status => {
  const userId = `account-${++counter}`;
  vi.mocked(api.getWorkspacePreference).mockRejectedValue(new AiApiError(status, {code: 'PLATFORM_AUTHORITY_UNAVAILABLE'}));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.error).toBeInstanceOf(AiApiError));
  expect(api.initializePersonalWorkspace).not.toHaveBeenCalled();
  expect(hook.result.current.current).toBeNull();
});
it('never automatically retries a restored initialization with an uncertain result', async () => {
  const userId = `account-${++counter}`;
  const operation = {kind: 'initialize', key: 'first-key'};
  writeValue(workspaceStorageKey(userId, 'workspaceOperation'), operation);
  vi.mocked(api.getWorkspacePreference).mockRejectedValue(new AiApiError(404, {code: 'PLATFORM_NOT_FOUND'}));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.loading).toBe(false));
  expect(api.initializePersonalWorkspace).not.toHaveBeenCalled();
  expect(hook.result.current.pending).toEqual(operation);
});
it('keeps rename target, version, contents and key across uncertain retries and refresh', async () => {
  const userId = `account-${++counter}`;
  vi.mocked(api.renameWorkspace).mockRejectedValue(new TypeError('network'));
  const first = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(first.result.current.current?.scope).toEqual(a));
  await act(async () => {
    await first.result.current.rename(view(), '新名称', '新描述');
  });
  const original = vi.mocked(api.renameWorkspace).mock.calls[0].slice(0, 2);
  first.unmount();
  const second = renderHook(() => useWorkspaceDirectory(userId));
  await act(async () => {
    await second.result.current.retry();
  });
  expect(vi.mocked(api.renameWorkspace).mock.calls[1].slice(0, 2)).toEqual(original);
  expect(original[0]).toEqual({scope: a, expectedVersion: 1, name: '新名称', description: '新描述'});
});
it('preserves idempotency conflicts rather than replacing the original operation key', async () => {
  const userId = `account-${++counter}`;
  vi.mocked(api.renameWorkspace).mockRejectedValue(new AiApiError(409, {code: 'PLATFORM_IDEMPOTENCY_CONFLICT'}));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current).not.toBeNull());
  await act(async () => {
    await hook.result.current.rename(view(), '改名', '');
  });
  const pending = hook.result.current.pending;
  await act(async () => {
    await hook.result.current.retry();
  });
  expect(hook.result.current.pending).toEqual(pending);
  expect(api.renameWorkspace).toHaveBeenCalledTimes(2);
});
it('refreshes a version conflict without automatically resubmitting a renamed space', async () => {
  const userId = `account-${++counter}`;
  vi.mocked(api.renameWorkspace).mockRejectedValue(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current).not.toBeNull());
  await act(async () => {
    await hook.result.current.rename(view(), '改名', '');
  });
  expect(hook.result.current.pending).toBeNull();
  expect(hook.result.current.warning).toContain('版本已更新');
  expect(api.renameWorkspace).toHaveBeenCalledTimes(1);
});
it('queries a known server receipt ID instead of issuing another write', async () => {
  const userId = `account-${++counter}`;
  writeValue(workspaceStorageKey(userId, 'workspaceOperation'), {
    kind: 'rename', key: 'original-key', operationId: 'known-operation',
    request: {scope: a, expectedVersion: 1, name: '改名', description: ''}
  });
  vi.mocked(api.getWorkspaceCommandReceipt).mockResolvedValue(receipt(view()));
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await act(async () => {
    await hook.result.current.retry();
  });
  expect(api.getWorkspaceCommandReceipt).toHaveBeenCalledWith('known-operation', expect.anything());
  expect(api.renameWorkspace).not.toHaveBeenCalled();
  expect(hook.result.current.pending).toBeNull();
});
it('requires module protection before a delayed selection is committed', async () => {
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  let proceed: (() => void) | undefined;
  const unregister = registerWorkspaceGuard(userId, (_scope, next) => {
    proceed = next;
  });
  await act(async () => {
    await hook.result.current.select(b);
  });
  expect(hook.result.current.current?.scope).toEqual(a);
  await act(async () => {
    proceed?.();
  });
  expect(hook.result.current.current?.scope).toEqual(b);
  unregister();
});
it('cancels stale selection responses and aborts observers when the account is cleared', async () => {
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  const shared = renderHook(() => useCurrentWorkspace(userId));
  await waitFor(() => expect(hook.result.current.current).not.toBeNull());
  let finish!: (response: ReturnType<typeof reply<api.WorkspaceView>>) => void;
  vi.mocked(api.getWorkspace).mockImplementation(() => new Promise(resolve => {
    finish = resolve;
  }));
  let selecting!: Promise<boolean>;
  act(() => {
    selecting = hook.result.current.select(b);
  });
  act(() => window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: userId})));
  await act(async () => {
    finish(reply(view(b)));
    await selecting;
  });
  expect(shared.result.current).toBeNull();
  expect(hook.result.current.current).toBeNull();
});
it('blocks new commands and navigation on a damaged operation snapshot', async () => {
  const userId = `account-${++counter}`;
  writeValue(workspaceStorageKey(userId, 'workspaceOperation'), {kind: 'rename', key: 'bad', request: {scope: a}});
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current).not.toBeNull());
  await act(async () => {
    await hook.result.current.rename(view(), '改名', '');
    await hook.result.current.select(b);
  });
  expect(hook.result.current.recoveryBlocked).toBe(true);
  expect(api.renameWorkspace).not.toHaveBeenCalled();
  expect(hook.result.current.current?.scope).toEqual(a);
});
it.each(['resolved', 'rejected'])('ignores a stale %s refresh started while a selection request was in flight', async outcome => {
  const userId = `account-${++counter}`;
  const hook = renderHook(() => useWorkspaceDirectory(userId));
  await waitFor(() => expect(hook.result.current.current?.scope).toEqual(a));
  let finishSelection!: (response: ReturnType<typeof reply<api.WorkspaceView>>) => void;
  let finishRefresh!: () => void;
  vi.mocked(api.getWorkspace).mockImplementation(scope => new Promise((resolve, reject) => {
    if (scope.workspaceId === 'b') finishSelection = resolve;
    else finishRefresh = () => outcome === 'resolved' ? resolve(reply(view(a))) : reject(new AiApiError(401, {code: 'UNAUTHORIZED'}));
  }));
  // Keep the preference write uncertain, so its post-write refresh cannot mask the competing old response.
  vi.mocked(api.updateWorkspacePreference).mockRejectedValue(new TypeError('network'));
  let selecting!: Promise<boolean>;
  let refreshing!: Promise<void>;
  act(() => {
    selecting = hook.result.current.select(b);
    refreshing = hook.result.current.load();
  });
  await waitFor(() => expect(finishRefresh).toBeTypeOf('function'));
  await act(async () => {
    finishSelection(reply(view(b)));
    await selecting;
  });
  await act(async () => {
    finishRefresh();
    await refreshing;
  });
  expect(hook.result.current.current?.scope).toEqual(b);
  expect(hook.result.current.error).toBeInstanceOf(TypeError);
});
