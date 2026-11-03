import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/model';
import {checkWorkspaceAccess} from '@/services/platform/workspace';
import {AiApiError} from '@/services/arte-ai/request';
import PersonalModelManager from './PersonalModelManager';
import {storageKey} from './storage';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/model', async original => ({
  ...(await original<typeof import('@/services/platform/model')>()),
  listManagedModels: vi.fn(),
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
      connectionTemplate: template,
      remoteModel: 'reviewed-model',
      contextWindowTokens: 8192,
      maxOutputTokens: 512
    }, version: 4, updatedAt: 'now'
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
  source: 'PERSONAL',
  catalogRevision: null,
  releaseRef: 'release',
  linkedBy: 'user',
  linkedAt: 'now'
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
    contextWindowTokens: 8192
  }]));
  vi.mocked(api.getModelValidation).mockResolvedValue(response(validation));
  vi.mocked(api.getPublicationStatus).mockResolvedValue(response({
    binding: release.binding,
    ready: false,
    reason: 'NO_COMPATIBLE_BUDGET',
    budgetRefs: [],
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

it('lists readable models without filtering by creator and creates using current scope only', async () => {
  manager();
  await screen.findByRole('button', {name: '管理 我的模型'});
  fireEvent.change(screen.getByRole('textbox', {name: '新模型名称'}), {target: {value: '个人模型'}});
  fireEvent.click(screen.getByRole('button', {name: '创建私有模型'}));
  await waitFor(() => expect(api.executeModelCommand).toHaveBeenCalledWith(expect.objectContaining({
    kind: 'create',
    request: {scope, name: '个人模型'}
  }), undefined));
  await screen.findByRole('button', {name: '保存模型草稿'});
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
  expect(screen.getByRole('button', {name: '创建私有模型'})).toBeDisabled();
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
it('shows a published version without budget and leaves creation/allowance to the server', async () => {
  vi.mocked(api.listModelVersions).mockResolvedValue(response({records: [release], current: 1, size: 10, total: 1}));
  manager();
  await open();
  fireEvent.click(screen.getByRole('button', {name: /reviewed-model\s*·\s*查看版本 v1/}));
  await screen.findByText('已发布，但缺少本人兼容预算。请联系空间负责人；发布不会创建预算或提高额度。');
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
  expect(api.listManagedModels).toHaveBeenLastCalledWith(scope, 'next', expect.anything());
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
  await screen.findByText('当前列表没有可读模型');
  await act(async () => finish(response(draft)));
  expect(screen.queryByLabelText('模型展示名称')).not.toBeInTheDocument();
  expect(api.listManagedModels).toHaveBeenLastCalledWith(other.scope, null, expect.anything());
});
it('reports disabled deployment without granting optimistic creation', async () => {
  vi.mocked(api.listManagedModels).mockRejectedValue(new AiApiError(404, {code: 'NOT_FOUND'}));
  manager();
  await screen.findByText('当前部署尚未开放模型管理，请联系管理员确认功能开关与部署。');
  expect(screen.getByRole('button', {name: '创建私有模型'})).toBeDisabled();
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
