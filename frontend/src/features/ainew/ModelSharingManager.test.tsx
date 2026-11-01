import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/modelSharing';
import * as modelApi from '@/services/platform/model';
import * as spaces from '@/services/platform/workspace';
import {sharingAccess, sharingContext, sharingModel, sharingView} from '@root/tests/fixtures/modelSharing';
import {saveSharingOperation} from './modelSharingRecovery';
import ModelSharingManager from './ModelSharingManager';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/modelSharing', async original => ({
  ...await original<typeof import('@/services/platform/modelSharing')>(),
  getModelSharing: vi.fn(),
  getModelAccess: vi.fn(),
  listModelGrants: vi.fn(),
  listTenantModelMembers: vi.fn(),
  executeSharingCommand: vi.fn()
}));
vi.mock('@/services/platform/model', async original => ({
  ...await original<typeof import('@/services/platform/model')>(),
  listManagedModels: vi.fn()
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(),
  browseAccessibleWorkspaces: vi.fn()
}));
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
let view: api.SharingView;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  view = structuredClone(sharingView);
  vi.mocked(modelApi.listManagedModels).mockResolvedValue(reply({records: [sharingModel], nextCursor: null}));
  vi.mocked(spaces.browseAccessibleWorkspaces).mockResolvedValue(reply({
    records: [
      {scope: sharingContext.scope, name: 'Home', state: 0, currentRole: 0, version: 1, defaultWorkspace: true},
      {
        scope: {tenantId: 'tenant', workspaceId: 'target'},
        name: 'Target',
        state: 0,
        currentRole: 0,
        version: 1,
        defaultWorkspace: false
      }], nextCursor: null, exhausted: true
  }));
  vi.mocked(api.getModelSharing).mockImplementation(async () => structuredClone(view));
  vi.mocked(api.getModelAccess).mockResolvedValue(sharingAccess);
  vi.mocked(api.listModelGrants).mockResolvedValue({current: 1, size: 20, total: 0, records: []});
  vi.mocked(api.listTenantModelMembers).mockResolvedValue({
    current: 1,
    size: 100,
    total: 1,
    records: [{subjectId: 'bob', role: 'MEMBER', state: 'ACTIVE'}]
  });
  vi.mocked(api.executeSharingCommand).mockImplementation(async command => {
    if (command.kind === 'create') return {...sharingModel, version: 0, name: command.request.name};
    if (command.kind === 'scopes') view.policy = {
      ...view.policy,
      version: command.request.expectedVersion + 1,
      workspaceIds: command.request.workspaceIds
    };
    if (command.kind === 'consumers') view.policy = {
      ...view.policy,
      version: command.request.expectedVersion + 1,
      credentialVersion: command.request.credentialVersion,
      subjectIds: command.request.subjectIds
    };
    return null;
  });
});
afterEach(cleanup);

async function selectModel() {
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '选择授权模型'}));
  fireEvent.click(await screen.findByText('Tenant model · 租户模型'));
  await screen.findByText(/策略版本 0/);
}

async function selectTarget() {
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '允许使用的空间'}));
  fireEvent.click(await screen.findByText('Target'));
}

it('creates an explicitly tenant-owned model without a credential or hidden grant', async () => {
  render(<ModelSharingManager context={sharingContext}/>);
  fireEvent.change(screen.getByRole('textbox', {name: '租户模型名称'}), {target: {value: 'New tenant model'}});
  fireEvent.click(screen.getByRole('button', {name: '创建租户私有模型'}));
  await screen.findByText('租户私有模型已创建，请到模型管理保存草稿、租户密钥并验证发布。');
  expect(api.executeSharingCommand).toHaveBeenCalledWith({
    kind: 'create',
    request: {scope: sharingContext.scope, name: 'New tenant model'}
  }, expect.any(String));
});
it('saves allowed spaces without implicitly approving model actions or credential consumers', async () => {
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  await selectTarget();
  fireEvent.click(screen.getByRole('button', {name: '保存允许使用的空间'}));
  await screen.findByText('操作已确认保存，已读取当前权限和版本。');
  expect(api.executeSharingCommand).toHaveBeenCalledWith({
    kind: 'scopes',
    request: {
      scope: sharingContext.scope,
      resourceId: sharingModel.resourceId,
      expectedVersion: 0,
      workspaceIds: ['home', 'target']
    }
  }, expect.any(String));
  expect(api.executeSharingCommand).toHaveBeenCalledTimes(1);
});
it('approves consumers only against the displayed current tenant credential version', async () => {
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '允许使用租户凭据的成员'}));
  fireEvent.click(await screen.findByText('MEMBER · bob'));
  fireEvent.click(screen.getByRole('button', {name: '保存租户凭据用途'}));
  await waitFor(() => expect(api.executeSharingCommand).toHaveBeenCalled());
  expect(api.executeSharingCommand).toHaveBeenCalledWith({
    kind: 'consumers',
    request: {
      scope: sharingContext.scope,
      resourceId: sharingModel.resourceId,
      expectedVersion: 0,
      credentialVersion: 1,
      subjectIds: ['bob']
    }
  }, expect.any(String));
});
it('keeps a conflicted scope draft and requires an explicit fresh read before another save', async () => {
  vi.mocked(api.executeSharingCommand).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  await selectTarget();
  fireEvent.click(screen.getByRole('button', {name: '保存允许使用的空间'}));
  await screen.findByText('版本已变化，草稿已保留。请重新读取并核对后再明确提交。');
  expect(screen.getByRole('button', {name: '保存允许使用的空间'})).toBeDisabled();
  view.policy.version = 5;
  fireEvent.click(screen.getByRole('button', {name: '重新读取并保留草稿'}));
  await screen.findByText(/策略版本 5/);
  fireEvent.click(screen.getByRole('button', {name: '保存允许使用的空间'}));
  await waitFor(() => expect(api.executeSharingCommand).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.executeSharingCommand).mock.calls[1][0]).toMatchObject({
    request: {
      expectedVersion: 5,
      workspaceIds: ['home', 'target']
    }
  });
});
it('restores exact original requests and keys without submitting on mount', async () => {
  const command: api.SharingCommand = {
    kind: 'scopes',
    request: {
      scope: sharingContext.scope,
      resourceId: sharingModel.resourceId,
      expectedVersion: 0,
      workspaceIds: ['target']
    }
  };
  saveSharingOperation(sharingContext, {scope: sharingContext.scope, key: 'original', command});
  render(<ModelSharingManager context={sharingContext}/>);
  await screen.findByText(/策略版本 0/);
  expect(api.executeSharingCommand).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '重试确认原授权操作'}));
  await waitFor(() => expect(api.executeSharingCommand).toHaveBeenCalledWith(command, 'original'));
});
it('blocks writes before transport when recovery storage fails', async () => {
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  await selectTarget();
  const write = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('unavailable');
  });
  try {
    fireEvent.click(screen.getByRole('button', {name: '保存允许使用的空间'}));
    await screen.findByText('恢复记录不可用，已阻止新操作；请保留本页并检查浏览器存储。');
    expect(api.executeSharingCommand).not.toHaveBeenCalled();
  } finally {
    write.mockRestore();
  }
});
it('does not treat personal credential ownership as permission to share its use', async () => {
  view.credential.kind = 'PERSONAL';
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  expect(screen.getByRole('button', {name: '保存租户凭据用途'})).toBeDisabled();
});
it('keeps responsible-subject credential controls separate from denied ACL management', async () => {
  vi.mocked(api.getModelAccess).mockRejectedValue(new AiApiError(403, {}));
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  expect(screen.getByRole('combobox', {name: '允许使用租户凭据的成员'})).not.toBeDisabled();
  expect(screen.getByRole('button', {name: '保存允许使用的空间'})).toBeDisabled();
  expect(screen.queryByRole('button', {name: '保存模型动作授权'})).not.toBeInTheDocument();
});
it('does not reapply an acknowledged operation when only the post-save read failed', async () => {
  vi.mocked(api.getModelSharing).mockResolvedValueOnce(view).mockRejectedValueOnce(new TypeError('network'));
  render(<ModelSharingManager context={sharingContext}/>);
  await selectModel();
  await selectTarget();
  fireEvent.click(screen.getByRole('button', {name: '保存允许使用的空间'}));
  await screen.findByText('操作已确认保存，但最新内容读取失败；只需重新读取，不要重复提交。');
  expect(api.executeSharingCommand).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole('button', {name: '重试确认原授权操作'})).not.toBeInTheDocument();
});
