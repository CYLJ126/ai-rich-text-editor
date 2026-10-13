import React from 'react';
import {act, cleanup, fireEvent, render, renderHook, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import WorkspacePicker from './WorkspacePicker';
import {useWorkspaceDirectory} from './useWorkspace';
import * as api from '@/services/platform/workspace';
import {AiApiError} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/workspace', async original => ({
  ...(await original<typeof import('@/services/platform/workspace')>()),
  getWorkspace: vi.fn(),
  getWorkspacePreference: vi.fn(),
  listAccessibleWorkspaces: vi.fn(),
  checkWorkspaceAccess: vi.fn(),
  initializePersonalWorkspace: vi.fn(),
  createWorkspace: vi.fn(),
  renameWorkspace: vi.fn(),
  updateWorkspacePreference: vi.fn(),
}));
const a = {tenantId: 'tenant-a', workspaceId: 'space-a'};
const b = {tenantId: 'tenant-b', workspaceId: 'space-b'};
const view = (scope = a): api.WorkspaceView => ({
  scope,
  name: scope.workspaceId === a.workspaceId ? '学习空间' : '协作空间',
  description: '原描述',
  currentRole: 0,
  state: 0,
  version: 7
});
const preference: api.WorkspacePreference = {
  defaultScope: a,
  startupScope: null,
  lastScope: null,
  favorites: [],
  version: 3
};
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const receipt = <T, >(value: T) => reply({
  operationId: 'operation', status: 1 as const, delivery: 0 as const, value,
  resultVersion: 8, acceptedAt: 'now', completedAt: 'now'
});
let userId = '';
let next = 0;
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  userId = `picker-user-${++next}`;
  vi.mocked(api.getWorkspace).mockImplementation(async scope => reply(view(scope)));
  vi.mocked(api.getWorkspacePreference).mockResolvedValue(reply(preference));
  vi.mocked(api.listAccessibleWorkspaces).mockResolvedValue({
    httpStatus: 200, body: {
      success: true,
      code: '200',
      desc: 'OK',
      records: [{...view(), defaultWorkspace: true}, {...view(b), defaultWorkspace: false}],
      current: 1,
      size: 20,
      total: 42,
    }
  });
  vi.mocked(api.checkWorkspaceAccess).mockResolvedValue(reply({allowed: true, reason: 'ALLOWED', checkedAt: 'now'}));
  vi.mocked(api.updateWorkspacePreference).mockImplementation(async request => receipt({
    ...preference,
    startupScope: request.startupScope, lastScope: request.lastScope, favorites: request.favorites, version: 4
  }));
});
afterEach(cleanup);

async function open() {
  render(<WorkspacePicker userId={userId}/>);
  await waitFor(() => expect(screen.getByLabelText('选择工作空间')).toHaveTextContent('学习空间'));
  fireEvent.click(screen.getByLabelText('选择工作空间'));
  await screen.findByRole('button', {name: '修改空间信息'});
}

it('shows real tenant IDs and roles, filters only this page and keeps server pagination', async () => {
  await open();
  expect(screen.getByText('所属租户：tenant-b')).toBeInTheDocument();
  expect(screen.getAllByText('负责人')).toHaveLength(2);
  expect(screen.queryByRole('button', {name: '管理成员'})).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('筛选本页空间'), {target: {value: '协作'}});
  expect(screen.queryByRole('button', {name: '学习空间'})).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '协作空间'})).toBeInTheDocument();
  expect(document.querySelector('[title="3"]')).toBeInTheDocument();
});
it('does not offer create or rename just because the current role is owner', async () => {
  vi.mocked(api.checkWorkspaceAccess).mockResolvedValue(reply({
    allowed: false,
    reason: 'POLICY_DENIED',
    checkedAt: 'now'
  }));
  render(<WorkspacePicker userId={userId}/>);
  await waitFor(() => expect(screen.getByLabelText('选择工作空间')).toHaveTextContent('学习空间'));
  fireEvent.click(screen.getByLabelText('选择工作空间'));
  await waitFor(() => expect(api.checkWorkspaceAccess).toHaveBeenCalledTimes(2));
  expect(screen.queryByRole('button', {name: '修改空间信息'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '新建工作空间'})).not.toBeInTheDocument();
});
it('retains and locks rename inputs on an uncertain response, then retries the same original command', async () => {
  vi.mocked(api.renameWorkspace).mockRejectedValue(new TypeError('network'));
  await open();
  fireEvent.click(screen.getByRole('button', {name: '修改空间信息'}));
  const dialog = (await screen.findByLabelText('空间名称')).closest<HTMLElement>('[role="dialog"]')!;
  fireEvent.change(within(dialog).getByLabelText('空间名称'), {target: {value: '新名称'}});
  fireEvent.change(within(dialog).getByLabelText('空间描述'), {target: {value: '新描述'}});
  fireEvent.click(within(dialog).getByRole('button', {name: /保\s*存/}));
  await waitFor(() => expect(api.renameWorkspace).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(within(dialog).getByLabelText('空间名称')).toBeDisabled());
  expect(within(dialog).getByLabelText('空间名称')).toHaveValue('新名称');
  const original = vi.mocked(api.renameWorkspace).mock.calls[0].slice(0, 2);
  await within(dialog).findByText('network');
  fireEvent.click(within(dialog).getByRole('button', {name: /重试确认原操作/}));
  await waitFor(() => expect(api.renameWorkspace).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.renameWorkspace).mock.calls[1].slice(0, 2)).toEqual(original);
  expect(original[0]).toEqual({scope: a, expectedVersion: 7, name: '新名称', description: '新描述'});
});
it('keeps a create form attached to its original tenant if another picker changes the current space', async () => {
  vi.mocked(api.createWorkspace).mockRejectedValue(new TypeError('network'));
  await open();
  fireEvent.click(screen.getByRole('button', {name: '新建工作空间'}));
  const dialog = (await screen.findByLabelText('空间名称')).closest<HTMLElement>('[role="dialog"]')!;
  fireEvent.change(within(dialog).getByLabelText('空间名称'), {target: {value: '创建名称'}});
  const other = renderHook(() => useWorkspaceDirectory(userId));
  await act(async () => {
    await other.result.current.select(b);
  });
  await waitFor(() => expect(other.result.current.saving).toBe(false));
  fireEvent.click(within(dialog).getByRole('button', {name: /保\s*存/}));
  await waitFor(() => expect(api.createWorkspace).toHaveBeenCalled());
  expect(api.createWorkspace).toHaveBeenCalledWith({
    tenantId: 'tenant-a',
    name: '创建名称',
    description: ''
  }, expect.any(String), expect.anything());
});
it('refreshes conflicts and preserves editable form content without silently retrying', async () => {
  vi.mocked(api.renameWorkspace).mockRejectedValue(new AiApiError(409, {
    code: 'PLATFORM_VERSION_CONFLICT',
    desc: '版本冲突'
  }));
  await open();
  fireEvent.click(screen.getByRole('button', {name: '修改空间信息'}));
  const dialog = (await screen.findByLabelText('空间名称')).closest<HTMLElement>('[role="dialog"]')!;
  fireEvent.change(within(dialog).getByLabelText('空间名称'), {target: {value: '保留输入'}});
  fireEvent.click(within(dialog).getByRole('button', {name: /保\s*存/}));
  await waitFor(() => expect(api.getWorkspacePreference).toHaveBeenCalledTimes(2));
  expect(within(dialog).getByLabelText('空间名称')).toHaveValue('保留输入');
  expect(api.renameWorkspace).toHaveBeenCalledTimes(1);
});
