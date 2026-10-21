import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/ai';
import WorkspaceAiSettings from './WorkspaceAiSettings';
import WorkspaceAiPreparation from './WorkspaceAiPreparation';
import {AiApiError} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/ai', async original => ({
  ...(await original<typeof import('@/services/platform/ai')>()),
  ensureWorkspaceAi: vi.fn(),
  getWorkspaceAi: vi.fn(),
  updateWorkspaceAi: vi.fn(),
  getAiCatalog: vi.fn(),
  importAiConfiguration: vi.fn(),
  publishAiCatalog: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const binding = {type: 'binding', id: 'model', version: 'v1'};
const rate = {type: 'rate', id: 'price', version: 'v1'};
const preset = {enabled: true, bindings: [binding], budgets: [{rate, limit: {amount: '100', currency: 'CNY'}}]};
const view: api.WorkspaceAiView = {
  mode: 'DATABASE', configuration: {scope, preset, templateVersion: 1, version: 0},
  workspaceVersion: 2, canManage: true, needsImport: false, defaults: preset,
  models: [{binding, name: 'Model', connection: {type: 'connection', id: 'connection', version: 'v1'}, rate}]
};
const response = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response(view));
  vi.mocked(api.ensureWorkspaceAi).mockResolvedValue(response(view));
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
  vi.mocked(api.ensureWorkspaceAi).mockImplementation(() => new Promise(resolve => {
    finish = () => resolve(response(view));
  }));
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy={false}><p>聊天消费者</p>
  </WorkspaceAiPreparation>);
  expect(screen.queryByText('聊天消费者')).not.toBeInTheDocument();
  expect(screen.getByText('正在准备空间 AI 配置…')).toBeInTheDocument();
  finish();
  await screen.findByText('聊天消费者');
  expect(api.ensureWorkspaceAi).toHaveBeenCalledWith(scope, expect.objectContaining({signal: expect.any(AbortSignal)}));
});
it('skips public provisioning for an existing manual legacy AI scope', () => {
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy><p>旧范围</p></WorkspaceAiPreparation>);
  expect(screen.getByText('旧范围')).toBeInTheDocument();
  expect(api.ensureWorkspaceAi).not.toHaveBeenCalled();
});
it('preserves the original operation and CAS fields after network uncertainty and remount', async () => {
  vi.mocked(api.updateWorkspaceAi).mockRejectedValueOnce(new TypeError('network'));
  render(<WorkspaceAiSettings scope={scope} context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '保存空间 AI 配置'}));
  await screen.findByText('保存结果待确认，请保留本页并按原操作重试。');
  const original = vi.mocked(api.updateWorkspaceAi).mock.calls[0];
  expect(original[0]).toMatchObject({scope, expectedWorkspaceVersion: 2, expectedVersion: 0, preset});
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
  vi.mocked(api.importAiConfiguration).mockResolvedValue(response({
    operationId: 'import', status: 1, delivery: 0,
    value: {version: 1, catalog: null}, resultVersion: 1, acceptedAt: 'now', completedAt: 'now'
  }));
  const updated = vi.fn();
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={updated}/>);
  expect(api.getWorkspaceAi).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '导入现有部署配置'}));
  await waitFor(() => expect(api.importAiConfiguration).toHaveBeenCalledTimes(1));
  expect(updated).toHaveBeenCalled();
});
it('keeps consumers unmounted after rejected provisioning and supports explicit recheck', async () => {
  vi.mocked(api.ensureWorkspaceAi).mockRejectedValueOnce(new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'}));
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy={false}><p>聊天消费者</p>
  </WorkspaceAiPreparation>);
  await screen.findByText('此空间暂不可用，请联系空间负责人或平台管理员确认访问权限与 AI 配置。');
  expect(screen.queryByText('聊天消费者')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '重新检查'}));
  await screen.findByText('聊天消费者');
});

const textCapability = {
  definition: {type: 'capability', id: 'text', version: 'v1'}, kind: 'GENERATION',
  inputSchema: {type: 'schema', id: 'generation-request', version: 'v1'},
  outputSchema: {type: 'schema', id: 'model-result', version: 'v1'},
  features: ['TEXT_INPUT', 'STREAMING', 'USAGE_REPORTING'], sideEffect: 'READ_ONLY', availability: 'EXECUTABLE'
};
const originalCatalog: api.AiCatalog = {
  capabilities: [textCapability],
  bindings: [{
    definition: binding, capability: textCapability, connection: view.models[0].connection,
    remoteOperation: 'deepseek-chat', contextWindowTokens: 65536, rate
  }],
  connections: [{definition: view.models[0].connection, providerId: 'deepseek', endpoint: 'https://example.invalid'}],
  rates: [{
    definition: rate,
    inputPerMillion: {amount: '1', currency: 'CNY'},
    outputPerMillion: {amount: '2', currency: 'CNY'}
  }],
  legacyGrants: [], legacyBudgets: [], defaults: preset
};

async function selectField(label: string, option: string) {
  fireEvent.mouseDown(screen.getByLabelText(label));
  fireEvent.click(await screen.findByText(option, {selector: '.ant-select-item-option-content'}));
}

async function prepareUpgrade() {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 1, catalog: structuredClone(originalCatalog)}));
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '发布新模型'}));
  fireEvent.change(screen.getByLabelText('模型标识'), {target: {value: 'model'}});
  fireEvent.change(screen.getByLabelText('固定版本'), {target: {value: 'v2'}});
  fireEvent.change(screen.getByLabelText('供应商模型名称'), {target: {value: 'deepseek-chat'}});
  fireEvent.change(screen.getByLabelText('上下文容量'), {target: {value: '65536'}});
  await selectField('已发布能力', 'text@v1 · 纯文本');
  fireEvent.click(await screen.findByRole('switch', {name: '为此模型发布工具调用能力'}));
  fireEvent.change(await screen.findByLabelText('新能力固定版本'), {target: {value: 'v2'}});
  await selectField('已审核连接', 'deepseek · connection');
  await selectField('固定费率', 'price · CNY');
}

it('publishes a new tool capability and model atomically while preserving old versions and workspace defaults', async () => {
  vi.mocked(api.publishAiCatalog).mockRejectedValueOnce(new TypeError('uncertain'));
  await prepareUpgrade();
  fireEvent.click(screen.getByRole('button', {name: /^(确\s*定|OK)$/}));
  await waitFor(() => expect(api.publishAiCatalog).toHaveBeenCalledTimes(1));
  const original = vi.mocked(api.publishAiCatalog).mock.calls[0];
  expect(original[0]).toMatchObject({
    expectedVersion: 1, catalog: {
      capabilities: [textCapability, {
        ...textCapability,
        definition: {...textCapability.definition, version: 'v2'},
        features: [...textCapability.features, 'TOOL_CALLS']
      }],
      bindings: [originalCatalog.bindings[0], {
        definition: {...binding, version: 'v2'},
        capability: {definition: {...textCapability.definition, version: 'v2'}}
      }],
      defaults: preset
    }
  });
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
  await screen.findByText('保存结果待确认，请保留本页并按原操作重试。');
  cleanup();
  render(<WorkspaceAiSettings scope={scope} context={context} adminOnly onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(api.publishAiCatalog).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.publishAiCatalog).mock.calls[1]).toEqual(original);
});
it('rejects reuse of an existing capability version before publishing', async () => {
  await prepareUpgrade();
  fireEvent.change(screen.getByLabelText('新能力固定版本'), {target: {value: 'v1'}});
  fireEvent.click(screen.getByRole('button', {name: /^(确\s*定|OK)$/}));
  await screen.findByText('请填写未发布的能力版本，例如 v2');
  expect(api.publishAiCatalog).not.toHaveBeenCalled();
});
