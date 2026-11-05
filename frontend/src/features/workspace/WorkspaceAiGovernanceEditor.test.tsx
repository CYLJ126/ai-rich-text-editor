import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/ai';
import WorkspaceAiGovernanceEditor from './WorkspaceAiGovernanceEditor';
import ConfigurationBoundary, {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';

vi.mock('@umijs/max', () => ({
  request: vi.fn(), history: {
    block: vi.fn(() => () => {
    })
  }
}));
vi.mock('@/services/platform/ai', async (original) => ({
  ...(await original<typeof import('@/services/platform/ai')>()),
  getWorkspaceAi: vi.fn(),
  getWorkspaceBudgetRules: vi.fn(),
  listWorkspaceBudgetRateOptions: vi.fn(),
  prepareWorkspaceAi: vi.fn(),
  updateWorkspaceBudgetRules: vi.fn(),
  updateWorkspaceAi: vi.fn(),
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const binding = {type: 'binding', id: 'model', version: 'v1'};
const rate = {type: 'rate', id: 'price', version: 'v1'};
const preset = {enabled: true, bindings: [binding], budgets: [{rate, limit: {amount: '100', currency: 'CNY'}}]};
const view: api.WorkspaceAiView = {
  mode: 'DATABASE',
  configuration: {scope, enabled: true, models: [{resourceId: 'resource', binding}], templateVersion: 1, version: 0},
  workspaceVersion: 2,
  canManage: true,
  needsImport: false,
  templateVersion: 1,
  defaults: preset,
  models: [{
    resourceId: 'resource',
    kind: 'PERSONAL',
    enabled: true,
    binding,
    name: 'Model',
    connection: {type: 'connection', id: 'connection', version: 'v1'},
    rate
  }]
};
const response = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response(view));
  vi.mocked(api.getWorkspaceBudgetRules).mockResolvedValue(response({
    scope, configuration: {scope, rules: preset.budgets, version: 0}, workspaceVersion: 2,
    canManage: true, canRaiseBudget: false, approvedCaps: preset.budgets
  }));
  vi.mocked(api.listWorkspaceBudgetRateOptions).mockResolvedValue(response({
    items: [{
      rate,
      kind: 'MODEL',
      currency: 'CNY',
      configured: true
    }], nextCursor: null
  }));
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response(view));
  vi.mocked(api.updateWorkspaceAi).mockResolvedValue(response({
    operationId: 'operation',
    status: 1,
    delivery: 0,
    value: {...view, workspaceVersion: 3, configuration: {...view.configuration!, version: 1}},
    resultVersion: 1,
    acceptedAt: 'now',
    completedAt: 'now'
  }));
});
afterEach(cleanup);

function ChangeTarget({done}: { done: () => void }) {
  const navigation = useConfigurationNavigation();
  return <button type="button" onClick={() => navigation.request(done)}>更换 AI 目标</button>;
}

it('requires explicit budget approval and only discards the draft when leaving', async () => {
  const done = vi.fn();
  render(<ConfigurationBoundary userId={context.userId}>
    <WorkspaceAiGovernanceEditor scope={scope} context={context} onUpdated={vi.fn()}/><ChangeTarget done={done}/>
  </ConfigurationBoundary>);
  fireEvent.change(await screen.findByLabelText('预算额度 price'), {target: {value: '40'}});
  fireEvent.click(screen.getByText('更换 AI 目标'));
  expect(screen.getByRole('button', {name: '保存并继续'})).toBeDisabled();
  expect(api.updateWorkspaceBudgetRules).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '放弃修改并继续'}));
  await waitFor(() => expect(done).toHaveBeenCalledOnce());
  expect(api.updateWorkspaceBudgetRules).not.toHaveBeenCalled();
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
});

it('shows the precise route model and only adds it to the draft on explicit request', async () => {
  const focused = view.models[0];
  const configuration = view.configuration;
  if (!configuration) throw new Error('Missing configuration fixture');
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response({
    ...view,
    configuration: {...configuration, enabled: false, models: []}
  }));
  render(<WorkspaceAiGovernanceEditor scope={scope} context={context} focusedModel={focused} onUpdated={vi.fn()}/>);
  await screen.findByText('空间 AI 尚未启用。此版本尚未配置在空间中。');
  expect(screen.getByRole('switch', {name: '启用此空间 AI'})).not.toBeChecked();
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '加入模型草稿'}));
  expect(screen.queryByRole('button', {name: '加入模型草稿'})).not.toBeInTheDocument();
  expect(screen.getByRole('switch', {name: '启用此空间 AI'})).not.toBeChecked();
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
  expect(api.updateWorkspaceBudgetRules).not.toHaveBeenCalled();
});
it('preserves the original operation and CAS fields after network uncertainty and remount', async () => {
  vi.mocked(api.updateWorkspaceAi).mockRejectedValueOnce(new TypeError('network'));
  render(<WorkspaceAiGovernanceEditor scope={scope} context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '保存空间 AI 配置'}));
  await screen.findByText('保存结果待确认，请保留本页并按原操作重试。');
  const original = vi.mocked(api.updateWorkspaceAi).mock.calls[0];
  expect(original[0]).toMatchObject({
    scope,
    expectedWorkspaceVersion: 2,
    expectedVersion: 0,
    enabled: true,
    models: [{resourceId: 'resource', binding}]
  });
  cleanup();
  render(<WorkspaceAiGovernanceEditor scope={scope} context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(api.updateWorkspaceAi).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.updateWorkspaceAi).mock.calls[1]).toEqual(original);
});

it('shows scope settings read-only when the server denies management eligibility', async () => {
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response({...view, canManage: false}));
  render(<WorkspaceAiGovernanceEditor scope={scope} context={context} onUpdated={vi.fn()}/>);
  expect(await screen.findByRole('button', {name: '保存空间 AI 配置'})).toBeDisabled();
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
});

it('saves budget rules independently without changing model selections', async () => {
  vi.mocked(api.updateWorkspaceBudgetRules).mockResolvedValue(response({
    operationId: 'rules', status: 1, delivery: 0, acceptedAt: 'now', completedAt: 'now', resultVersion: 1,
    value: {
      scope,
      configuration: {scope, rules: preset.budgets, version: 1},
      workspaceVersion: 3,
      canManage: true,
      canRaiseBudget: false,
      approvedCaps: preset.budgets
    }
  }));
  render(<WorkspaceAiGovernanceEditor scope={scope} context={context} onUpdated={vi.fn()}/>);
  fireEvent.change(await screen.findByLabelText('预算额度 price'), {target: {value: '40'}});
  fireEvent.click(screen.getByRole('button', {name: '保存独立预算规则'}));
  await waitFor(() => expect(api.updateWorkspaceBudgetRules).toHaveBeenCalledTimes(1));
  expect(vi.mocked(api.updateWorkspaceBudgetRules).mock.calls[0][0]).toMatchObject({
    scope, expectedWorkspaceVersion: 2, expectedRuleVersion: 0,
    rules: [{rate, limit: {amount: '40', currency: 'CNY'}}]
  });
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
});

it('first preparation is explicit and contains no model or account request', async () => {
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response({...view, configuration: null}));
  vi.mocked(api.getWorkspaceBudgetRules).mockResolvedValue(response({
    scope,
    configuration: null,
    workspaceVersion: 2,
    canManage: true,
    canRaiseBudget: false,
    approvedCaps: []
  }));
  render(<WorkspaceAiGovernanceEditor scope={scope} context={context} onUpdated={vi.fn()}/>);
  const button = await screen.findByRole('button', {name: '首次准备空间 AI 配置'});
  expect(api.prepareWorkspaceAi).not.toHaveBeenCalled();
  fireEvent.click(button);
  await waitFor(() => expect(api.prepareWorkspaceAi).toHaveBeenCalledTimes(1));
  expect(vi.mocked(api.prepareWorkspaceAi).mock.calls[0][0]).toEqual({
    scope,
    expectedWorkspaceVersion: 2,
    expectedTemplateVersion: 1
  });
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
  expect(api.updateWorkspaceBudgetRules).not.toHaveBeenCalled();
});
