import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/ai';
import WorkspaceAiSettings from './WorkspaceAiSettings';
import WorkspaceAiPreparation from './WorkspaceAiPreparation';
import {AiApiError} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/ai', async (original) => ({
  ...(await original<typeof import('@/services/platform/ai')>()),
  getWorkspaceAi: vi.fn(),
  getWorkspaceBudgetRules: vi.fn(),
  listWorkspaceBudgetRateOptions: vi.fn(),
  prepareWorkspaceAi: vi.fn(),
  updateWorkspaceBudgetRules: vi.fn(),
  updateWorkspaceAi: vi.fn(),
  getAiCatalog: vi.fn(),
  initializeAiCatalog: vi.fn(),
  updateDefaultAiPolicy: vi.fn()
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

it('prepares scope configuration before mounting conversation and discovery consumers', async () => {
  let finish!: () => void;
  vi.mocked(api.getWorkspaceAi).mockImplementation(() => new Promise((resolve) => {
    finish = () => resolve(response(view));
  }));
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy={false}><p>聊天消费者</p>
  </WorkspaceAiPreparation>);
  expect(screen.queryByText('聊天消费者')).not.toBeInTheDocument();
  expect(screen.getByText('正在读取空间 AI 配置…')).toBeInTheDocument();
  finish();
  await screen.findByText('聊天消费者');
  expect(api.getWorkspaceAi).toHaveBeenCalledWith(scope, expect.objectContaining({signal: expect.any(AbortSignal)}));
});
it('skips public provisioning for an existing manual legacy AI scope', () => {
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy><p>旧范围</p></WorkspaceAiPreparation>);
  expect(screen.getByText('旧范围')).toBeInTheDocument();
  expect(api.getWorkspaceAi).not.toHaveBeenCalled();
});
it('preserves the original operation and CAS fields after network uncertainty and remount', async () => {
  vi.mocked(api.updateWorkspaceAi).mockRejectedValueOnce(new TypeError('network'));
  render(<WorkspaceAiSettings scope={scope} context={context} onUpdated={vi.fn()}/>);
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
  render(<WorkspaceAiSettings scope={scope} context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(api.updateWorkspaceAi).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.updateWorkspaceAi).mock.calls[1]).toEqual(original);
});
it('shows scope settings read-only when the server denies management eligibility', async () => {
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response({...view, canManage: false}));
  render(<WorkspaceAiSettings scope={scope} context={context} onUpdated={vi.fn()}/>);
  expect(await screen.findByRole('button', {name: '保存空间 AI 配置'})).toBeDisabled();
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
});
it('exposes explicit initial import to a platform administrator without requiring a public space', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 0, catalog: null}));
  vi.mocked(api.initializeAiCatalog).mockResolvedValue(response({
    operationId: 'import', status: 1, delivery: 0,
    value: {version: 1, catalog: null}, resultVersion: 1, acceptedAt: 'now', completedAt: 'now'
  }));
  const updated = vi.fn();
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={updated}/>);
  expect(api.getWorkspaceAi).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '初始化空目录'}));
  await waitFor(() => expect(api.initializeAiCatalog).toHaveBeenCalledTimes(1));
  expect(updated).toHaveBeenCalled();
});
it('keeps consumers unmounted after rejected provisioning and supports explicit recheck', async () => {
  vi.mocked(api.getWorkspaceAi).mockRejectedValueOnce(new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'}));
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy={false}><p>聊天消费者</p>
  </WorkspaceAiPreparation>);
  await screen.findByText('此空间暂不可用，请联系空间负责人或平台管理员确认访问权限与 AI 配置。');
  expect(screen.queryByText('聊天消费者')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '重新检查'}));
  await screen.findByText('聊天消费者');
});

const originalCatalog: api.AiCatalog = {
  reviewedPlans: [], connections: [],
  rates: [{
    definition: rate,
    inputPerMillion: {amount: '1', currency: 'CNY'},
    outputPerMillion: {amount: '2', currency: 'CNY'}
  }],
  defaults: {enabled: true, rateCaps: [{rate, limit: {amount: '100', currency: 'CNY'}}]}
};

it('publishes only default switch and rate caps and retries an uncertain operation with the same request and key', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 1, catalog: structuredClone(originalCatalog)}));
  vi.mocked(api.updateDefaultAiPolicy).mockRejectedValueOnce(new TypeError('uncertain'));
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  expect(await screen.findByRole('button', {name: '发布默认 AI 模板'})).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '发布新模型'})).not.toBeInTheDocument();
  expect(screen.queryByLabelText('新空间默认模型')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('默认预算额度 price'), {target: {value: '80'}});
  fireEvent.click(screen.getByRole('button', {name: '发布默认 AI 模板'}));
  await waitFor(() => expect(api.updateDefaultAiPolicy).toHaveBeenCalledTimes(1));
  const original = vi.mocked(api.updateDefaultAiPolicy).mock.calls[0];
  expect(original[0]).toEqual({
    expectedCatalogVersion: 1,
    enabled: true,
    rateCaps: [{rate, limit: {amount: '80', currency: 'CNY'}}]
  });
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
  await screen.findByText('保存结果待确认，请保留本页并按原操作重试。');
  cleanup();
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(api.updateDefaultAiPolicy).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.updateDefaultAiPolicy).mock.calls[1]).toEqual(original);
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
  render(<WorkspaceAiSettings scope={scope} context={context} onUpdated={vi.fn()}/>);
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
  render(<WorkspaceAiSettings scope={scope} context={context} onUpdated={vi.fn()}/>);
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

it('does not publish when the original command cannot be stored', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 1, catalog: structuredClone(originalCatalog)}));
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  await screen.findByRole('button', {name: '发布默认 AI 模板'});
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('storage unavailable');
  });
  try {
    fireEvent.click(screen.getByRole('button', {name: '发布默认 AI 模板'}));
    await screen.findByText('无法保存原请求和操作键，本次未提交。恢复浏览器存储后确认原操作。');
    expect(api.updateDefaultAiPolicy).not.toHaveBeenCalled();
  } finally {
    storage.mockRestore();
  }
});
it('after confirmed policy success and a failed reread, remount confirmation only rereads', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValueOnce(response({version: 1, catalog: structuredClone(originalCatalog)}))
    .mockRejectedValueOnce(new TypeError('read unavailable'));
  vi.mocked(api.updateDefaultAiPolicy).mockResolvedValue(response({
    operationId: 'policy',
    status: 1,
    delivery: 0,
    value: {version: 2, catalog: structuredClone(originalCatalog)},
    resultVersion: 2,
    acceptedAt: 'now',
    completedAt: 'now'
  }));
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '发布默认 AI 模板'}));
  await screen.findByText('默认模板已确认，但当前目录读取失败。确认原操作只会重新读取，不再次发布。');
  cleanup();
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 2, catalog: structuredClone(originalCatalog)}));
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(screen.queryByRole('button', {name: '重试确认原配置操作'})).not.toBeInTheDocument());
  expect(api.updateDefaultAiPolicy).toHaveBeenCalledTimes(1);
});
