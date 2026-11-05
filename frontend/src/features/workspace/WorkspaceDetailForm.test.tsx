import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/workspace';
import * as ai from '@/services/platform/ai';
import * as policy from '@/services/platform/policy';
import {AiApiError} from '@/services/arte-ai/request';
import {tenantPolicyView, workspacePolicyView} from '@root/tests/fixtures/policy';
import WorkspaceDetailForm from './WorkspaceDetailForm';
import ConfigurationBoundary, {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';

vi.mock('@umijs/max', () => ({
  request: vi.fn(), history: {
    block: vi.fn(() => () => {
    })
  }
}));
vi.mock('@/services/platform/ai', async original => ({
  ...await original<typeof import('@/services/platform/ai')>(),
  getWorkspaceAi: vi.fn(), getWorkspaceBudgetRules: vi.fn(), listWorkspaceBudgetRateOptions: vi.fn(),
  updateWorkspaceAi: vi.fn(), updateWorkspaceBudgetRules: vi.fn(), prepareWorkspaceAi: vi.fn(),
}));
vi.mock('@/services/platform/policy', async original => ({
  ...await original<typeof import('@/services/platform/policy')>(), getPolicy: vi.fn(), executePolicyCommand: vi.fn(),
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
const aiView = (canManage = true): ai.WorkspaceAiView => ({
  mode: 'DATABASE', workspaceVersion: 7, canManage, needsImport: false,
  templateVersion: 1, models: [], defaults: null,
  configuration: {scope, version: 0, enabled: true, models: [], templateVersion: 1},
});
let counter = 0;
let userId: string;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  userId = `details-${++counter}`;
  vi.mocked(ai.getWorkspaceAi).mockResolvedValue(reply(aiView()));
  vi.mocked(ai.getWorkspaceBudgetRules).mockResolvedValue(reply({
    scope, configuration: {scope, rules: [], version: 0}, workspaceVersion: 7,
    canManage: true, canRaiseBudget: false, approvedCaps: []
  }));
  vi.mocked(ai.listWorkspaceBudgetRateOptions).mockResolvedValue(reply({
    items: [],
    nextCursor: null,
    scope,
    workspaceVersion: 7,
    ruleVersion: 0,
    catalogVersion: 1
  }));
  vi.mocked(policy.getPolicy).mockImplementation(async (kind, target) => kind === 'tenant'
    ? {kind, value: {...tenantPolicyView, policy: {...tenantPolicyView.policy, tenantId: target.tenantId}}}
    : {
      kind, value: {
        ...workspacePolicyView, workspaceVersion: 7,
        policy: {...workspacePolicyView.policy, scope: target as api.WorkspaceScope}
      }
    });
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

function ChangeTarget({done}: { done: () => void }) {
  const navigation = useConfigurationNavigation();
  return <button type="button" onClick={() => navigation.request(done)}>更换详情目标</button>;
}

it('saves metadata before a guarded target change and keeps the chat preference unchanged', async () => {
  const done = vi.fn();
  render(<ConfigurationBoundary userId={userId}>
    <WorkspaceDetailForm userId={userId} scope={scope}/><ChangeTarget done={done}/>
  </ConfigurationBoundary>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toHaveValue('空间 B'));
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: '离开前保存'}});
  fireEvent.click(screen.getByText('更换详情目标'));
  expect(done).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '保存并继续'}));
  await waitFor(() => expect(done).toHaveBeenCalledOnce());
  expect(api.renameWorkspace).toHaveBeenCalledWith(expect.objectContaining({
    scope,
    name: '离开前保存'
  }), expect.any(String), expect.anything());
  expect(api.updateWorkspacePreference).not.toHaveBeenCalled();
});

it('keeps the AI draft and its target through a metadata save, then uses the confirmed parent version', async () => {
  const saved = vi.fn();
  render(<WorkspaceDetailForm userId={userId} scope={scope} onSaved={saved}/>);
  await screen.findByText('已启用 · 已配置 0 个模型');
  fireEvent.click(screen.getByRole('tab', {name: 'AI 配置'}));
  fireEvent.click(await screen.findByRole('switch', {name: '启用此空间 AI'}));
  fireEvent.click(screen.getByRole('tab', {name: '概览'}));
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: '新的 B'}});
  fireEvent.click(screen.getByRole('button', {name: '保存空间信息'}));
  await waitFor(() => expect(saved).toHaveBeenCalledWith(expect.objectContaining({scope, name: '新的 B'})));
  expect(api.renameWorkspace).toHaveBeenCalledWith({
    scope, expectedVersion: 7, name: '新的 B', description: '原描述'
  }, expect.any(String), expect.anything());
  expect(api.checkWorkspaceAccess).toHaveBeenCalledWith(scope, 'workspace', 'workspace:update', expect.anything());
  fireEvent.click(screen.getByRole('tab', {name: 'AI 配置'}));
  expect(screen.getByRole('switch', {name: '启用此空间 AI'})).not.toBeChecked();
  const updated = aiView();
  vi.mocked(ai.updateWorkspaceAi).mockResolvedValue(reply({
    operationId: 'operation', status: 1, delivery: 0, resultVersion: 1, acceptedAt: 'now', completedAt: 'now',
    value: {
      ...updated,
      workspaceVersion: 9,
      configuration: {scope, enabled: false, models: [], templateVersion: 1, version: 1}
    }
  }));
  vi.mocked(ai.getWorkspaceAi).mockResolvedValue(reply({...updated, workspaceVersion: 9}));
  fireEvent.click(screen.getByRole('button', {name: '保存空间 AI 配置'}));
  await waitFor(() => expect(ai.updateWorkspaceAi).toHaveBeenCalledWith({
    scope, expectedWorkspaceVersion: 8, expectedVersion: 0, enabled: false, models: [],
  }, expect.any(String)));
  expect(ai.updateWorkspaceBudgetRules).not.toHaveBeenCalled();
  expect(api.updateWorkspacePreference).not.toHaveBeenCalled();
});

it('keeps AI fields unavailable until the new space is saved and does not select it for chat', async () => {
  const saved = vi.fn();
  render(<WorkspaceDetailForm userId={userId} tenants={[{value: 'tenant', label: '租户'}]} onSaved={saved}/>);
  expect(screen.queryByRole('tab', {name: 'AI 配置'})).not.toBeInTheDocument();
  expect(ai.getWorkspaceAi).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByRole('combobox', {name: '新空间所属租户'})).toBeEnabled());
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '新空间所属租户'}));
  fireEvent.click(await screen.findByText('租户', {selector: '.ant-select-item-option-content'}));
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('空间名称'), {target: {value: '新空间'}});
  fireEvent.click(screen.getByRole('button', {name: '创建工作空间'}));
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
  expect(screen.queryByRole('tab', {name: 'AI 配置'})).not.toBeInTheDocument();
});


it('opens the AI tab with the exact route model and does not submit any changes', async () => {
  const reference = (type: string, id: string) => ({type, id, version: 'v1'});
  const model: ai.AiModel = {
    name: '跨页定位模型', resourceId: 'model', binding: reference('binding', 'model'),
    kind: 'TENANT', enabled: true, rate: reference('rate', 'price'), connection: reference('connection', 'provider')
  };
  vi.mocked(ai.getWorkspaceAi).mockResolvedValue(reply({...aiView(), models: [model]}));
  render(<WorkspaceDetailForm userId={userId} scope={scope} tab="ai" focusedModel={model}/>);
  expect(await screen.findByText('跨页定位模型 · 版本 v1')).toBeInTheDocument();
  expect(await screen.findByRole('button', {name: '加入模型草稿'})).toBeEnabled();
  expect(ai.updateWorkspaceAi).not.toHaveBeenCalled();
  expect(ai.updateWorkspaceBudgetRules).not.toHaveBeenCalled();
});
it('keeps metadata, AI and policy permissions independent for a space administrator', async () => {
  vi.mocked(api.getWorkspace).mockResolvedValue(reply({...view(), currentRole: 1}));
  vi.mocked(ai.getWorkspaceAi).mockResolvedValue(reply(aiView(false)));
  vi.mocked(policy.getPolicy).mockImplementation(async kind => {
    if (kind === 'workspace') throw new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'});
    return {
      kind: 'tenant',
      value: {...tenantPolicyView, policy: {...tenantPolicyView.policy, tenantId: scope.tenantId}}
    };
  });
  render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  await waitFor(() => expect(screen.getByLabelText('空间名称')).toBeEnabled());
  expect(screen.getByText('管理员')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', {name: 'AI 配置'}));
  await screen.findByText('只有当前空间负责人可以修改空间 AI 设置。');
  expect(screen.getByRole('button', {name: '保存空间 AI 配置'})).toBeDisabled();
  expect(ai.getWorkspaceBudgetRules).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('tab', {name: '政策'}));
  await screen.findByText('当前无权读取或修改空间政策；需要当前空间负责人的治理资格。');
  expect(await screen.findByRole('button', {name: '查看租户政策'})).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '保存空间政策'})).not.toBeInTheDocument();
});

it('preserves the inline policy draft and updates its CAS version even when the tenant ceiling is hidden', async () => {
  const readPolicy = vi.mocked(policy.getPolicy).getMockImplementation();
  if (!readPolicy) throw new Error('Missing policy fixture');
  vi.mocked(policy.getPolicy).mockImplementation(async (kind, target) => {
    if (kind === 'tenant') throw new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'});
    return readPolicy(kind, target);
  });
  render(<WorkspaceDetailForm userId={userId} scope={scope}/>);
  await screen.findByRole('tab', {name: '概览'});
  fireEvent.click(screen.getByRole('tab', {name: '政策'}));
  const pane = await screen.findByLabelText('空间政策编辑');
  expect(await screen.findByText(/你当前无权查看完整租户政策/)).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '查看租户政策'})).not.toBeInTheDocument();
  fireEvent.click(await within(pane).findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(screen.getByRole('tab', {name: '概览'}));
  fireEvent.change(screen.getByLabelText('空间描述'), {target: {value: '新描述'}});
  fireEvent.click(screen.getByRole('button', {name: '保存空间信息'}));
  await screen.findByText('空间信息已保存。');
  fireEvent.click(screen.getByRole('tab', {name: '政策'}));
  expect(within(pane).getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
  fireEvent.click(within(pane).getByRole('button', {name: '保存空间政策'}));
  await waitFor(() => expect(policy.executePolicyCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'workspace', request: expect.objectContaining({scope, expectedWorkspaceVersion: 8})
  }), expect.any(String)));
});
