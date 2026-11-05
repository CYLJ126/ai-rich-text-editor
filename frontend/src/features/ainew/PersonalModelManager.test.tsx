import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/model';
import * as usage from '@/services/platform/modelUsage';
import {notifyAiConfiguration} from '@/features/workspace/aiConfigurationEvents';
import {checkWorkspaceAccess} from '@/services/platform/workspace';
import {AiApiError} from '@/services/arte-ai/request';
import PersonalModelManager from './PersonalModelManager';
import {storageKey} from './storage';
import ConfigurationBoundary, {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';

vi.mock('./ModelSharingManager', () => ({default: () => <p>模型授权详情</p>}));
vi.mock('@umijs/max', () => ({
  request: vi.fn(), history: {
    block: vi.fn(() => () => {
    })
  }
}));
vi.mock('@/services/platform/model', async original => ({
  ...(await original<typeof import('@/services/platform/model')>()),
  listManagedModels: vi.fn(),
  getModelCreationOptions: vi.fn(),
  getManagedModel: vi.fn(),
  getModelDraft: vi.fn(),
  checkModelActions: vi.fn(),
  listModelTemplates: vi.fn(),
  listModelVersions: vi.fn(),
  listPublicationOptions: vi.fn(),
  getPublicationStatus: vi.fn(),
  getModelValidation: vi.fn(),
  executeModelCommand: vi.fn(),
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...(await original<typeof import('@/services/platform/workspace')>()),
  checkWorkspaceAccess: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'ordinary-user', scope};
const ref = (type: string, id: string, version = 'v1') => ({type, id, version});
const template = ref('connection', 'reviewed');
const model: api.ManagedModel = {
  resourceId: 'model',
  scope,
  name: '我的模型',
  createdBy: 'not-the-current-user',
  state: 'DRAFT',
  bindingId: null,
  version: 0,
  createdAt: 'now'
};
const draft: api.ModelDraft = {
  draft: {
    resource: {tenantId: scope.tenantId, type: 'model', resourceId: 'model'}, scope, name: '我的模型',
    settings: {
      planRef: ref('model-plan', 'reviewed-model'),
      contextWindowTokens: 8192,
      maxOutputTokens: 512
    },
    plan: {
      planRef: ref('model-plan', 'reviewed-model'),
      displayName: 'Reviewed',
      connection: template,
      remoteModel: 'reviewed-model',
      features: ['TEXT_INPUT', 'STREAMING'],
      contextWindowTokens: 8192,
      rate: ref('rate', 'reviewed-price'),
      publishedAt: 'now',
      publishedBy: 'admin',
      catalogRevision: 1
    },
    version: 4,
    updatedAt: 'now'
  },
  credential: {resourceId: 'model', state: 'CONFIGURED', version: 2, mayMaintain: true}
};
const validation: api.ModelValidation = {
  validationId: 'validation', resourceId: 'model', draftVersion: 4, credentialVersion: 2,
  state: 'SUCCEEDED', code: 'MODEL_FOUND', expiresAt: 'now', checkedAt: new Date().toISOString()
};
const release: api.ModelVersion = {
  resourceId: 'model',
  binding: ref('binding', 'personal-model'),
  capability: ref('capability', 'personal-cap'),
  connection: ref('connection', 'personal-conn'),
  providerId: 'test',
  remoteOperation: 'reviewed-model',
  contextWindowTokens: 8192,
  rate: ref('rate', 'reviewed-price'),
  source: 'RESOURCE',
  catalogRevision: null,
  releaseRef: 'release',
  linkedBy: 'user',
  linkedAt: 'now', planRef: ref('model-plan', 'reviewed-model'), credentialKind: 'PERSONAL'
};
const response = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const receipt = (value: unknown) => ({
  operationId: 'operation',
  status: 1 as const,
  delivery: 0 as const,
  value,
  resultVersion: 1,
  acceptedAt: 'now',
  completedAt: 'now'
});
const allActions = () => new Set(api.MODEL_ACTIONS);
const updated = vi.fn();
const unsafe = vi.fn();

function manager(value = context) {
  return render(<PersonalModelManager context={value} onUpdated={updated} onUnsafeChange={unsafe}/>);
}

async function open() {
  fireEvent.click(await screen.findByRole('button', {name: '管理 我的模型'}));
  await screen.findByRole('button', {name: '保存模型草稿'});
}

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  vi.mocked(api.getModelCreationOptions).mockResolvedValue(response(['PERSONAL', 'TENANT']));
  vi.mocked(checkWorkspaceAccess).mockResolvedValue(response({allowed: true, reason: 'ALLOWED', checkedAt: 'now'}));
  vi.mocked(api.listManagedModels).mockResolvedValue(response({records: [model], nextCursor: null}));
  vi.mocked(api.getManagedModel).mockResolvedValue(response(model));
  vi.mocked(api.getModelDraft).mockResolvedValue(response(draft));
  vi.mocked(api.checkModelActions).mockResolvedValue(allActions());
  vi.mocked(api.listModelTemplates).mockResolvedValue(response([{
    reference: template,
    providerId: 'test',
    protocol: ref('protocol', 'chat-completions-sse')
  }]));
  vi.mocked(api.listModelVersions).mockResolvedValue(response({records: [], current: 1, size: 10, total: 0}));
  vi.mocked(api.listPublicationOptions).mockResolvedValue(response([{
    planRef: ref('model-plan', 'reviewed-model'),
    rate: ref('rate', 'reviewed-price'),
    catalogRevision: 7,
    contextWindowTokens: 8192,
    displayName: 'Reviewed',
    connection: template,
    remoteModel: 'reviewed-model',
    features: ['TEXT_INPUT', 'STREAMING']
  }]));
  vi.mocked(api.getModelValidation).mockResolvedValue(response(validation));
  vi.mocked(api.getPublicationStatus).mockResolvedValue(response({
    binding: release.binding,
    valid: true,
    reason: 'VALID',
    maxOutputTokens: 512
  }));
  vi.mocked(api.executeModelCommand).mockImplementation(async command => {
    if (command.kind === 'create') return receipt(model) as never;
    if (command.kind === 'save') return receipt({...draft.draft!, version: 5}) as never;
    if (command.kind === 'credential' || command.kind === 'revoke') return receipt({
      ...draft.credential,
      version: 3
    }) as never;
    if (command.kind === 'validate') return {receipt: receipt(validation), current: validation} as never;
    if (command.kind === 'publish') return receipt(release) as never;
    return receipt({...model, state: 'DISABLED', version: 1}) as never;
  });
});
afterEach(cleanup);

function ChangeTarget({done}: { done: () => void }) {
  const navigation = useConfigurationNavigation();
  return <button type="button" onClick={() => navigation.request(done)}>更换模型管理目标</button>;
}

it('saves only an ordinary model draft before a guarded target change', async () => {
  const done = vi.fn();
  render(<ConfigurationBoundary userId={context.userId}>
    <PersonalModelManager context={context} onUpdated={updated}/><ChangeTarget done={done}/>
  </ConfigurationBoundary>);
  await open();
  fireEvent.change(screen.getByLabelText('模型展示名称'), {target: {value: '保存的草稿'}});
  fireEvent.click(screen.getByText('更换模型管理目标'));
  fireEvent.click(screen.getByRole('button', {name: '保存并继续'}));
  await waitFor(() => expect(done).toHaveBeenCalledOnce());
  expect(api.executeModelCommand).toHaveBeenCalledOnce();
  expect(vi.mocked(api.executeModelCommand).mock.calls[0][0].kind).toBe('save');
});

it('clears temporary secret input only after explicit discard, without submitting credentials', async () => {
  const done = vi.fn();
  render(<ConfigurationBoundary userId={context.userId}>
    <PersonalModelManager context={context} onUpdated={updated}/><ChangeTarget done={done}/>
  </ConfigurationBoundary>);
  await open();
  fireEvent.change(screen.getByLabelText('个人 API 密钥'), {target: {value: 'temporary-test-input'}});
  fireEvent.click(screen.getByText('更换模型管理目标'));
  expect(screen.getByRole('button', {name: '保存并继续'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button', {name: '放弃修改并继续'}));
  await waitFor(() => expect(done).toHaveBeenCalledOnce());
  expect(api.executeModelCommand).not.toHaveBeenCalled();
  expect(screen.getByLabelText('个人 API 密钥')).toHaveValue('');
});

it('lists readable models without filtering by creator and creates using current scope only', async () => {
  manager();
  await screen.findByRole('button', {name: '管理 我的模型'});
  expect(screen.getByText(/点击左侧模型以编辑/)).toBeInTheDocument();
  expect(screen.queryByText('模型授权与使用范围')).not.toBeInTheDocument();
  expect(screen.queryByText('模型授权详情')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '添加模型'}));
  fireEvent.change(screen.getByRole('textbox', {name: '新模型名称'}), {target: {value: '个人模型'}});
  fireEvent.click(screen.getByRole('button', {name: '创建私有模型'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'create',
    request: {scope, name: '个人模型', modelKind: 'PERSONAL'}
  }), undefined));
  await screen.findByRole('button', {name: '保存模型草稿'});
  expect(screen.getByText('模型授权与使用范围')).toBeInTheDocument();
  expect(screen.getByText('模型授权详情')).toBeInTheDocument();
  expect(updated).toHaveBeenCalledWith({kind: 'create', resourceId: 'model'});
});
it('keeps model read permission and personal credential responsibility separate', async () => {
  vi.mocked(api.getModelDraft).mockResolvedValue(response({
    ...draft,
    credential: {...draft.credential, mayMaintain: false}
  }));
  manager();
  await open();
  expect(screen.getByRole('button', {name: '保存模型草稿'})).toBeEnabled();
  expect(screen.getByLabelText('个人 API 密钥')).toBeDisabled();
  expect(screen.getByRole('button', {name: '验证当前连接'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '撤销本人密钥'})).toBeDisabled();
});
it('disables each write when server action checks deny it', async () => {
  vi.mocked(api.checkModelActions).mockResolvedValue(new Set());
  manager();
  await open();
  for (const name of ['保存模型草稿', '验证当前连接', '发布新的固定版本', '停用模型全部版本']) expect(screen.getByRole('button', {name})).toBeDisabled();
  expect(api.executeModelCommand).not.toHaveBeenCalled();
});
it('saves against the draft version instead of the management or credential version', async () => {
  manager();
  await open();
  fireEvent.change(screen.getByRole('textbox', {name: '模型展示名称'}), {target: {value: '新草稿名称'}});
  fireEvent.click(screen.getByRole('button', {name: '保存模型草稿'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'save',
    request: expect.objectContaining({scope, resourceId: 'model', expectedVersion: 4, name: '新草稿名称'})
  }), undefined));
});
it('does not overwrite after a draft CAS conflict and requires an explicit reread', async () => {
  vi.mocked(api.executeModelCommand).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  manager();
  await open();
  fireEvent.change(screen.getByRole('textbox', {name: '模型展示名称'}), {target: {value: '本地修改'}});
  fireEvent.click(screen.getByRole('button', {name: '保存模型草稿'}));
  await screen.findByText('版本或验证依据已变化。请重新读取当前内容，再检查配置、密钥和验证结果；不会自动覆盖或重新发布。');
  expect(api.executeModelCommand).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('textbox', {name: '模型展示名称'})).toHaveValue('本地修改');
  expect(screen.queryByRole('button', {name: '重试确认原操作'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '放弃修改并读取当前内容'}));
  await waitFor(() => expect(screen.getByRole('textbox', {name: '模型展示名称'})).toHaveValue('我的模型'));
});
it('requires confirmation before disabling all versions and submits the management version', async () => {
  vi.mocked(api.getManagedModel).mockResolvedValue(response({...model, state: 'PUBLISHED', version: 7}));
  manager();
  await open();
  fireEvent.click(screen.getByRole('button', {name: '停用模型全部版本'}));
  await screen.findByText('停用此模型的全部版本？将阻止新调用，保留历史记录。');
  expect(api.executeModelCommand).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '确认停用'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'disable',
    request: {scope, resourceId: 'model', expectedVersion: 7}
  }), undefined));
});
it('validates once then publishes exact dependencies and never applies a chat selection', async () => {
  manager();
  await open();
  expect(screen.getByRole('button', {name: '发布新的固定版本'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button', {name: '验证当前连接'}));
  await screen.findByText('认证成功且发现目标模型，可检查审核方案后发布。');
  fireEvent.click(screen.getByRole('button', {name: '发布新的固定版本'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'publish', request: {
      scope, resourceId: 'model', expectedVersion: 0, draftVersion: 4, credentialVersion: 2, validationId: 'validation',
      planRef: ref('model-plan', 'reviewed-model'), catalogRevision: 7,
    }
  }), undefined));
  expect(localStorage.setItem).not.toHaveBeenCalled();
});
it('recovers an uncertain save with its original request and key after remount without auto-submit', async () => {
  vi.mocked(api.executeModelCommand).mockRejectedValueOnce(new TypeError('network'));
  manager();
  await open();
  fireEvent.click(screen.getByRole('button', {name: '保存模型草稿'}));
  await screen.findByRole('button', {name: '重试确认原操作'});
  const original = vi.mocked(api.executeModelCommand).mock.calls[0];
  cleanup();
  manager();
  await screen.findByRole('button', {name: '重试确认原操作'});
  expect(api.executeModelCommand).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(unsafe).toHaveBeenLastCalledWith(true));
  fireEvent.click(screen.getByRole('button', {name: '重试确认原操作'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.executeModelCommand).mock.calls[1]).toEqual(original);
});
it('never persists or replays a credential from storage; asks for the same input after remount', async () => {
  vi.mocked(api.executeModelCommand).mockRejectedValueOnce(new TypeError('network'));
  manager();
  await open();
  fireEvent.change(screen.getByLabelText('个人 API 密钥'), {target: {value: 'only-memory-key'}});
  fireEvent.click(screen.getByRole('button', {name: '保存或替换本人密钥'}));
  await screen.findByRole('button', {name: '重试确认原操作'});
  const original = vi.mocked(api.executeModelCommand).mock.calls[0];
  expect((screen.getByLabelText('个人 API 密钥') as HTMLInputElement).value).toBe('');
  for (let i = 0; i < sessionStorage.length; i++) expect(sessionStorage.getItem(sessionStorage.key(i)!)).not.toContain('only-memory-key');
  expect(localStorage.setItem).not.toHaveBeenCalled();
  cleanup();
  manager();
  expect(await screen.findByRole('button', {name: '重试确认原操作'})).toBeDisabled();
  fireEvent.change(screen.getByLabelText('原操作密钥'), {target: {value: 'only-memory-key'}});
  fireEvent.click(screen.getByRole('button', {name: '重试确认原操作'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.executeModelCommand).mock.calls[1]).toEqual(original);
});
it('retains the credential recovery record on idempotency mismatch', async () => {
  vi.mocked(api.executeModelCommand).mockRejectedValue(new AiApiError(409, {code: 'PLATFORM_IDEMPOTENCY_CONFLICT'}));
  manager();
  await open();
  fireEvent.change(screen.getByLabelText('个人 API 密钥'), {target: {value: 'wrong-original-key'}});
  fireEvent.click(screen.getByRole('button', {name: '保存或替换本人密钥'}));
  await screen.findByRole('button', {name: '重试确认原操作'});
  await waitFor(() => expect(screen.getByRole('button', {name: '重试确认原操作'})).toBeEnabled());
  expect(sessionStorage.getItem(storageKey(context, 'model-management'))).toContain('credential');
  expect(screen.getByRole('button', {name: '添加模型'})).toBeDisabled();
});
it('retains the temporary credential when the same account and scope receive a new context object', async () => {
  vi.mocked(api.executeModelCommand).mockRejectedValueOnce(new TypeError('network'));
  const page = manager();
  await open();
  fireEvent.change(screen.getByLabelText('个人 API 密钥'), {target: {value: 'same-scope-memory-key'}});
  fireEvent.click(screen.getByRole('button', {name: '保存或替换本人密钥'}));
  await screen.findByRole('button', {name: '重试确认原操作'});
  const original = vi.mocked(api.executeModelCommand).mock.calls[0];
  page.rerender(<PersonalModelManager context={{...context, scope: {...scope}}} onUpdated={updated}/>);
  await waitFor(() => expect(screen.getByRole('button', {name: '重试确认原操作'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '重试确认原操作'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.executeModelCommand).mock.calls[1]).toEqual(original);
});
it('separates valid publication dependencies from workspace enablement and personal budget', async () => {
  vi.mocked(api.listModelVersions).mockResolvedValue(response({records: [release], current: 1, size: 10, total: 1}));
  manager();
  await open();
  fireEvent.click(screen.getByRole('button', {name: /reviewed-model\s*·\s*查看版本 v1/}));
  await screen.findByText('发布依赖有效；聊天还需目标空间启用和本人预算。');
  expect(api.executeModelCommand).not.toHaveBeenCalled();
});
it('supports empty filtered pages with a continuation cursor', async () => {
  vi.mocked(api.listManagedModels).mockResolvedValueOnce(response({
    records: [],
    nextCursor: 'next'
  })).mockResolvedValueOnce(response({records: [model], nextCursor: null}));
  manager();
  fireEvent.click(await screen.findByRole('button', {name: '继续查找可读模型'}));
  await screen.findByRole('button', {name: '管理 我的模型'});
  expect(api.listManagedModels).toHaveBeenLastCalledWith(scope, 'next', expect.anything(), {});
});
it('resets pagination when search changes without losing an open model draft', async () => {
  vi.mocked(api.listManagedModels).mockResolvedValueOnce(response({records: [model], nextCursor: 'old'}))
    .mockResolvedValueOnce(response({records: [], nextCursor: 'filtered'}))
    .mockResolvedValueOnce(response({records: [], nextCursor: null}));
  render(<PersonalModelManager context={context} scopeFilter={<span>管理空间选择</span>} onUpdated={updated}/>);
  await open();
  fireEvent.change(screen.getByRole('textbox', {name: '模型展示名称'}), {target: {value: '保留的草稿'}});
  const search = screen.getByRole('searchbox', {name: '搜索模型名称'});
  fireEvent.change(search, {target: {value: '目标'}});
  fireEvent.keyDown(search, {key: 'Enter'});
  await waitFor(() => expect(api.listManagedModels).toHaveBeenLastCalledWith(scope, null, expect.anything(), {search: '目标'}));
  expect(screen.getByRole('textbox', {name: '模型展示名称'})).toHaveValue('保留的草稿');
  fireEvent.click(await screen.findByRole('button', {name: '继续查找可读模型'}));
  await waitFor(() => expect(api.listManagedModels).toHaveBeenLastCalledWith(scope, 'filtered', expect.anything(), {search: '目标'}));
});
it('discards late reads after scope changes and never shows another scope response', async () => {
  let finish!: (value: ReturnType<typeof response<api.ModelDraft>>) => void;
  vi.mocked(api.getModelDraft).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }));
  const page = manager();
  fireEvent.click(await screen.findByRole('button', {name: '管理 我的模型'}));
  const other = {...context, scope: {...scope, workspaceId: 'other'}};
  vi.mocked(api.listManagedModels).mockResolvedValue(response({records: [], nextCursor: null}));
  page.rerender(<PersonalModelManager context={other} onUpdated={updated}/>);
  await screen.findByText('没有匹配的可读模型。');
  await act(async () => finish(response(draft)));
  expect(screen.queryByLabelText('模型展示名称')).not.toBeInTheDocument();
  expect(api.listManagedModels).toHaveBeenLastCalledWith(other.scope, null, expect.anything(), {});
});
it('reports disabled deployment without granting optimistic creation', async () => {
  vi.mocked(api.listManagedModels).mockRejectedValue(new AiApiError(404, {code: 'NOT_FOUND'}));
  manager();
  await screen.findByText('当前部署尚未开放模型管理，请联系管理员确认功能开关与部署。');
  expect(screen.getByRole('button', {name: '添加模型'})).toBeDisabled();
});
it('guards unsaved fields and clears secret input and detail on logout', async () => {
  manager();
  await open();
  fireEvent.change(screen.getByRole('textbox', {name: '模型展示名称'}), {target: {value: '未保存'}});
  await waitFor(() => expect(unsafe).toHaveBeenLastCalledWith(true));
  fireEvent.change(screen.getByLabelText('个人 API 密钥'), {target: {value: 'temporary-only'}});
  act(() => window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: context.userId})));
  await screen.findByText('账号会话已结束，请重新登录后打开模型管理。');
  expect(screen.queryByLabelText('个人 API 密钥')).not.toBeInTheDocument();
  expect(screen.queryByDisplayValue('temporary-only')).not.toBeInTheDocument();
});
it('never sends a write when recovery storage fails', async () => {
  manager();
  await open();
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('quota');
  });
  fireEvent.click(screen.getByRole('button', {name: '保存模型草稿'}));
  await screen.findByText('无法保存恢复记录，本次未提交。恢复浏览器存储后，请重试原操作。');
  expect(api.executeModelCommand).not.toHaveBeenCalled();
  storage.mockRestore();
  fireEvent.click(screen.getByRole('button', {name: '重试确认原操作'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledTimes(1));
});

it('creates tenant resources in the same detail flow without a second tab or refresh', async () => {
  manager();
  await screen.findByRole('button', {name: '管理 我的模型'});
  fireEvent.click(screen.getByRole('button', {name: '添加模型'}));
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '模型归属'}));
  fireEvent.click(await screen.findByText('租户模型', {selector: '.ant-select-item-option-content'}));
  fireEvent.change(screen.getByRole('textbox', {name: '新模型名称'}), {target: {value: '租户模型'}});
  fireEvent.click(screen.getByRole('button', {name: '创建私有模型'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'create', request: {scope, name: '租户模型', modelKind: 'TENANT'}
  }), undefined));
  await screen.findByRole('button', {name: '保存模型草稿'});
  expect(updated).toHaveBeenCalledWith({kind: 'create', resourceId: 'model'});
});

it('reads and refreshes returned execution B while preserving management M and making no budget write', async () => {
  const execution = {...scope, workspaceId: 'execution-B'};
  const readUsage = vi.spyOn(usage, 'getModelUsagePreparation').mockResolvedValue({
    scope: execution,
    resource: {tenantId: scope.tenantId, type: 'model', resourceId: 'model'},
    binding: {...release.binding, type: 'binding'},
    rate: {...release.rate, type: 'rate'},
    state: 'READY',
    reason: 'READY',
    allowedActions: [],
    budgetRefs: ['execution-B-budget'],
    approvedLimit: {amount: '10', currency: 'CNY'}
  });
  const writeUsage = vi.spyOn(usage, 'prepareModelUsage');
  vi.mocked(api.listModelVersions).mockResolvedValue(response({records: [release], current: 1, size: 10, total: 1}));
  vi.mocked(api.getManagedModel).mockResolvedValue(response({
    ...model,
    state: 'PUBLISHED',
    bindingId: release.binding.id
  }));
  render(<PersonalModelManager context={context} executionScope={execution}
                               initialModel={{resourceId: release.resourceId, binding: release.binding}}
                               onUpdated={updated}/>);
  await waitFor(() => expect(readUsage).toHaveBeenCalledWith({
    scope: execution,
    resourceId: 'model',
    binding: release.binding
  }, expect.anything()));
  expect(api.getManagedModel).toHaveBeenCalledWith(scope, 'model', expect.anything());
  act(() => notifyAiConfiguration(context.userId, execution));
  await waitFor(() => expect(readUsage).toHaveBeenCalledTimes(2));
  expect(api.getManagedModel).toHaveBeenCalledTimes(1);
  expect(writeUsage).not.toHaveBeenCalled();
  readUsage.mockRestore();
  writeUsage.mockRestore();
});

it('does not expose executable tenant creation when the server only allows personal models', async () => {
  vi.mocked(api.getModelCreationOptions).mockResolvedValue(response(['PERSONAL']));
  manager();
  await screen.findByRole('button', {name: '管理 我的模型'});
  fireEvent.click(screen.getByRole('button', {name: '添加模型'}));
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '模型归属'}));
  expect(screen.queryByText('租户模型', {selector: '.ant-select-item-option-content'})).not.toBeInTheDocument();
});

it('opens a separate initial creation form and cancels without changing the selected model', async () => {
  manager();
  await open();
  fireEvent.click(screen.getByRole('button', {name: '添加模型'}));
  expect(screen.queryByRole('textbox', {name: '模型展示名称'})).not.toBeInTheDocument();
  expect(screen.queryByText(/点击左侧模型以编辑/)).not.toBeInTheDocument();
  expect(screen.queryByText('模型授权与使用范围')).not.toBeInTheDocument();
  expect(screen.queryByText('模型授权详情')).not.toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox', {name: '新模型名称'}), {target: {value: '未保存的新模型'}});
  expect(screen.getByRole('button', {name: '管理 我的模型'})).toHaveAttribute('aria-disabled', 'true');
  fireEvent.click(screen.getByRole('button', {name: '取消添加模型'}));
  expect(screen.getByRole('textbox', {name: '模型展示名称'})).toHaveValue('我的模型');
  expect(screen.getByText('模型授权详情')).toBeInTheDocument();
  expect(api.executeModelCommand).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '添加模型'}));
  expect(screen.getByRole('textbox', {name: '新模型名称'})).toHaveValue('');
});
