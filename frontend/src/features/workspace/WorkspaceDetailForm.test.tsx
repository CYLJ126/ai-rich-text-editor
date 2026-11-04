import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/workspace';
import WorkspaceDetailForm from './WorkspaceDetailForm';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/features/ainew/WorkspaceAiGovernanceEditor', () => ({
  default: ({scope, disabled, onUpdated}: {
    scope: api.WorkspaceScope; disabled: boolean; onUpdated: () => void
  }) => <section aria-label="AI 表单"><span>AI 目标 {scope.workspaceId}</span>
    <button disabled={disabled}>编辑 AI 测试</button>
    <button disabled={disabled} onClick={onUpdated}>保存 AI 测试</button>
  </section>
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(),
  getWorkspace: vi.fn(), getWorkspacePreference: vi.fn(), browseAccessibleWorkspaces: vi.fn(),
  checkWorkspaceAccess: vi.fn(), createWorkspace: vi.fn(), renameWorkspace: vi.fn(), updateWorkspacePreference: vi.fn(),
  getWorkspaceCommandReceipt: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'B'};
const view = (target = scope): api.WorkspaceView => ({
  scope: target,
  name: `空间 ${target.workspaceId}`,
  description: '原描述',
  state: 0,
  currentRole: 0,
  version: 7
});
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const receipt = (value: api.WorkspaceView) => reply({
  operationId: 'operation', status: 1 as const, delivery: 0 as const,
  value, resultVersion: value.version, acceptedAt: 'now', completedAt: 'now'
});
let counter = 0;
let userId: string;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  userId = `details-${++counter}`;
  vi.mocked(api.getWorkspace).mockImplementation(async target => reply(view(target)));
  vi.mocked(api.checkWorkspaceAccess).mockResolvedValue(reply({allowed: true, reason: 'ALLOWED', checkedAt: 'now'}));
  vi.mocked(api.getWorkspacePreference).mockResolvedValue(reply({
    defaultScope: scope,
    startupScope: null,
    lastScope: null,
    favorites: [],
    version: 0
  }));
  vi.mocked(api.browseAccessibleWorkspaces).mockResolvedValue(reply({
    records: [{...view(), defaultWorkspace: true}],
    nextCursor: null,
    exhausted: true
  }));
  vi.mocked(api.renameWorkspace).mockImplementation(async request => receipt({
    ...view(request.scope),
    name: request.name,
    description: request.description,
    version: 8
  }));
  vi.mocked(api.createWorkspace).mockImplementation(async request => receipt({
    ...view({
      tenantId: request.tenantId,
      workspaceId: 'new'
    }), name: request.name, description: request.description
  }));
});
afterEach(cleanup);

it('reads and edits the exact supplied scope and locks AI while metadata is dirty', async () => {
  const saved = vi.fn();
  render(<WorkspaceDetailForm userId={userId} scope={scope} onSaved={saved}/>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toHaveValue('空间 B'));
  expect(api.checkWorkspaceAccess).toHaveBeenCalledWith(scope, 'workspace', 'workspace:update', expect.anything());
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: '新的 B'}});
  expect(screen.getByRole('button', {name: '编辑 AI 测试'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button', {name: '保存空间信息'}));
  await waitFor(() => expect(saved).toHaveBeenCalledWith(expect.objectContaining({scope, name: '新的 B'})));
  expect(api.renameWorkspace).toHaveBeenCalledWith({
    scope,
    expectedVersion: 7,
    name: '新的 B',
    description: '原描述'
  }, expect.any(String), expect.anything());
  expect(api.updateWorkspacePreference).not.toHaveBeenCalled();
});
it('keeps AI fields unavailable until the new space is saved and does not select it for chat', async () => {
  const saved = vi.fn();
  render(<WorkspaceDetailForm userId={userId} tenants={[{value: 'tenant', label: '租户'}]} onSaved={saved}/>);
  expect(screen.getByLabelText('启用此空间 AI')).toBeDisabled();
  expect(screen.queryByRole('region', {name: 'AI 表单'})).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('combobox', {name: '新空间所属租户'})).toBeEnabled());
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '新空间所属租户'}));
  fireEvent.click(await screen.findByText('租户', {selector: '.ant-select-item-option-content'}));
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: '新空间'}});
  fireEvent.click(screen.getByRole('button', {name: '保存空间信息'}));
  await waitFor(() => expect(saved).toHaveBeenCalledWith(expect.objectContaining({
    scope: {
      tenantId: 'tenant',
      workspaceId: 'new'
    }
  })));
  expect(api.createWorkspace).toHaveBeenCalledWith({
    tenantId: 'tenant',
    name: '新空间',
    description: ''
  }, expect.any(String), expect.anything());
  expect(api.getWorkspace).not.toHaveBeenCalled();
  expect(api.updateWorkspacePreference).not.toHaveBeenCalled();
});
it('preserves the original metadata request across remount and retries the same key', async () => {
  vi.mocked(api.renameWorkspace).mockRejectedValueOnce(new TypeError('network'));
  render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toHaveValue('空间 B'));
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: '保留草稿'}});
  fireEvent.click(screen.getByRole('button', {name: '保存空间信息'}));
  await screen.findByRole('button', {name: '重试确认原空间信息操作'});
  const original = vi.mocked(api.renameWorkspace).mock.calls[0].slice(0, 2);
  cleanup();
  render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toHaveValue('保留草稿'));
  await waitFor(() => expect(screen.getByRole('button', {name: '重试确认原空间信息操作'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '重试确认原空间信息操作'}));
  await waitFor(() => expect(api.renameWorkspace).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.renameWorkspace).mock.calls[1].slice(0, 2)).toEqual(original);
});
it('uses action permission for metadata editing even when the displayed role is owner', async () => {
  vi.mocked(api.checkWorkspaceAccess).mockResolvedValue(reply({allowed: false, reason: 'DENIED', checkedAt: 'now'}));
  render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  await screen.findByText('当前没有修改此空间信息的权限。');
  expect(screen.getByLabelText('空间名称')).toBeDisabled();
  expect(screen.getByRole('button', {name: '保存空间信息'})).toBeDisabled();
  expect(api.renameWorkspace).not.toHaveBeenCalled();
});
it('discards late details from a previous space and clears the form on logout', async () => {
  let finish!: (value: ReturnType<typeof reply<api.WorkspaceView>>) => void;
  vi.mocked(api.getWorkspace).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }));
  const page = render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  page.rerender(<WorkspaceDetailForm userId={userId} scope={{...scope, workspaceId: 'C'}}/>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toHaveValue('空间 C'));
  await act(async () => finish(reply(view())));
  expect(screen.getByLabelText('空间名称')).toHaveValue('空间 C');
  act(() => window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: userId})));
  expect(screen.getByLabelText('空间名称')).toHaveValue('');
  expect(screen.queryByRole('region', {name: 'AI 表单'})).not.toBeInTheDocument();
});


it('rereads the workspace concurrency version after saving AI before allowing metadata edits', async () => {
  render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toHaveValue('空间 B'));
  vi.mocked(api.getWorkspace).mockResolvedValue(reply({...view(), version: 9}));
  fireEvent.click(screen.getByRole('button', {name: '保存 AI 测试'}));
  await waitFor(() => expect(api.getWorkspace).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: 'AI保存后改名'}});
  fireEvent.click(screen.getByRole('button', {name: '保存空间信息'}));
  await waitFor(() => expect(api.renameWorkspace).toHaveBeenCalledWith(expect.objectContaining({
    scope,
    expectedVersion: 9
  }), expect.any(String), expect.anything()));
});
