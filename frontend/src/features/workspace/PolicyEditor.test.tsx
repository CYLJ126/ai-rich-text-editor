import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/policy';
import PolicyEditor from './PolicyEditor';
import {
  connectionOptions,
  policyRules,
  policyScope,
  tenantPolicyView,
  workspacePolicyView
} from '@root/tests/fixtures/policy';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/policy', async original => ({
  ...(await original<typeof import('@/services/platform/policy')>()), getPolicy: vi.fn(), executePolicyCommand: vi.fn(),
  getPolicyConnectionOptions: vi.fn(), previewWorkspacePolicy: vi.fn(),
}));
let tenant: api.TenantPolicyView;
let workspace: api.WorkspacePolicyView;
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.getPolicyConnectionOptions).mockImplementation(async (kind, scope) => connectionOptions(kind, scope));
  vi.mocked(api.previewWorkspacePolicy).mockImplementation(async (basis, rules) => ({
    scope: basis.policy.scope, workspaceVersion: basis.workspaceVersion, workspacePolicyVersion: basis.policy.version,
    tenantPolicyVersion: basis.tenantPolicyVersion, proposed: {effective: rules, limitedFields: []}
  }));
  sessionStorage.clear();
  tenant = structuredClone(tenantPolicyView);
  workspace = structuredClone(workspacePolicyView);
  vi.mocked(api.getPolicy).mockImplementation(async kind => kind === 'tenant'
    ? {kind, value: structuredClone(tenant)} : {kind, value: structuredClone(workspace)});
  vi.mocked(api.executePolicyCommand).mockImplementation(async command => {
    if (command.kind === 'tenant') tenant = {
      ...tenant, tenantVersion: command.request.expectedTenantVersion + 1,
      policy: {
        ...tenant.policy,
        rules: command.request.rules,
        membersMayCreateWorkspace: command.request.membersMayCreateWorkspace,
        version: command.request.expectedPolicyVersion + 1
      }
    };
    else workspace = {
      ...workspace, workspaceVersion: command.request.expectedWorkspaceVersion + 1,
      policy: {...workspace.policy, rules: command.request.rules, version: command.request.expectedPolicyVersion + 1},
      effect: {effective: command.request.rules, limitedFields: []}
    };
  });
});
afterEach(cleanup);

async function draft(kind: api.PolicyKind = 'tenant') {
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  const save = screen.getByRole('button', {name: kind === 'tenant' ? '保存租户政策' : '保存空间政策'});
  await waitFor(() => expect(save).toBeEnabled());
  return save;
}

it('edits personal tenant policy with complete rules and versions from the same read', async () => {
  const saved = vi.fn();
  render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant" onSaved={saved}/>);
  const save = await draft();
  expect(screen.getByText('个人租户名称 · 个人租户政策')).toBeInTheDocument();
  expect(api.executePolicyCommand).not.toHaveBeenCalled();
  fireEvent.click(save);
  await screen.findByText('政策已保存，已重新读取当前版本。');
  expect(api.executePolicyCommand).toHaveBeenCalledWith({
    kind: 'tenant', request: {
      tenantId: policyScope.tenantId,
      expectedTenantVersion: 8,
      expectedPolicyVersion: 3,
      membersMayCreateWorkspace: true,
      rules: {
        ...policyRules,
        personalCredentialsAllowed: true,
        allowedProtocols: ['existing-protocol', 'chat-completions-sse'],
        allowedProviders: ['existing-provider', 'deepseek'],
        allowedDestinations: ['existing.example', 'api.deepseek.com']
      },
    }
  }, expect.any(String));
  expect(saved).toHaveBeenCalledTimes(1);
});

it('preserves an uncertain command over remount and retries only its original key and complete request', async () => {
  vi.mocked(api.executePolicyCommand).mockRejectedValueOnce(new TypeError('network'));
  render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>);
  fireEvent.click(await draft());
  await screen.findByRole('button', {name: '重试确认原政策操作'});
  const original = vi.mocked(api.executePolicyCommand).mock.calls[0];
  cleanup();
  render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>);
  await screen.findByRole('button', {name: '重试确认原政策操作'});
  expect(api.executePolicyCommand).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', {name: '重试确认原政策操作'}));
  await screen.findByText('政策已保存，已重新读取当前版本。');
  expect(vi.mocked(api.executePolicyCommand).mock.calls[1]).toEqual(original);
});

it('keeps a conflicted draft and explicitly rebases its versions only after a fresh read', async () => {
  vi.mocked(api.executePolicyCommand).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>);
  fireEvent.click(await draft());
  await screen.findByText('政策或父记录版本已变化，草稿已保留。请读取最新版本并核对差异后再保存。');
  expect(screen.getByRole('button', {name: '保存租户政策'})).toBeDisabled();
  tenant = {...tenant, tenantVersion: 15, policy: {...tenant.policy, version: 10, membersMayCreateWorkspace: false}};
  fireEvent.click(screen.getByRole('button', {name: '读取最新版本并保留草稿'}));
  await screen.findByText('已读取最新版本并保留完整草稿。保存将以草稿替换当前政策，请核对下方变更预览。');
  expect(screen.getByRole('switch', {name: '允许成员创建工作空间'})).toBeChecked();
  expect(api.executePolicyCommand).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', {name: '保存租户政策'}));
  await screen.findByText('政策已保存，已重新读取当前版本。');
  expect(vi.mocked(api.executePolicyCommand).mock.calls[1][0].request).toMatchObject({
    expectedTenantVersion: 15, expectedPolicyVersion: 10,
    membersMayCreateWorkspace: true, rules: {personalCredentialsAllowed: true}
  });
  expect(vi.mocked(api.executePolicyCommand).mock.calls[1][1]).not.toBe(vi.mocked(api.executePolicyCommand).mock.calls[0][1]);
});

it('keeps workspace governance usable when tenant policy read is denied', async () => {
  vi.mocked(api.getPolicy).mockImplementation(async kind => {
    if (kind === 'tenant') throw new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'});
    return {kind, value: workspace};
  });
  render(<><PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>
    <PolicyEditor userId="alice" scope={policyScope} kind="workspace" workspaceName="研究空间"/></>);
  await screen.findByText('当前无权读取或修改租户政策；需要租户负责人或管理员的治理资格。空间政策可单独检查。');
  const region = within(screen.getByLabelText('空间政策编辑'));
  fireEvent.click(await region.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(region.getByRole('button', {name: '保存空间政策'}));
  await region.findByText('政策已保存，已重新读取当前版本。');
  expect(vi.mocked(api.executePolicyCommand).mock.calls[0][0]).toMatchObject({
    kind: 'workspace', request: {
      scope: policyScope, expectedWorkspaceVersion: 9, expectedPolicyVersion: 4, expectedTenantPolicyVersion: 3,
    }
  });
});

it('keeps the workspace draft editable after a definite tenant-ceiling rejection', async () => {
  vi.mocked(api.executePolicyCommand).mockRejectedValueOnce(new AiApiError(403, {code: 'PLATFORM_POLICY_DENIED'}));
  render(<PolicyEditor userId="alice" scope={policyScope} kind="workspace"/>);
  fireEvent.click(await draft('workspace'));
  await screen.findByText('空间政策超出租户上限。请收紧空间规则，或先由租户治理者调整租户政策。');
  expect(screen.queryByRole('button', {name: '重试确认原政策操作'})).not.toBeInTheDocument();
  expect(screen.getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
  expect(screen.getByRole('button', {name: '保存空间政策'})).toBeEnabled();
});

it('sends no command when browser recovery storage fails', async () => {
  render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>);
  const save = await draft();
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('unavailable');
  });
  try {
    fireEvent.click(save);
    await screen.findByText('政策恢复记录无法可靠保存或读取，已阻止新操作。请保留本页并检查浏览器存储。');
    expect(api.executePolicyCommand).not.toHaveBeenCalled();
  } finally {
    storage.mockRestore();
  }
});

it('retains a dirty workspace draft when a tenant save requests a refresh', async () => {
  const {rerender} = render(<PolicyEditor userId="alice" scope={policyScope} kind="workspace" refreshRevision={0}/>);
  await draft('workspace');
  rerender(<PolicyEditor userId="alice" scope={policyScope} kind="workspace" refreshRevision={1}/>);
  await screen.findByText('空间政策或租户上限已更新，当前草稿或原操作已保留，请重新核对后继续。');
  expect(api.getPolicy).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
  expect(screen.getByRole('button', {name: '保存空间政策'})).toBeDisabled();
});

it('ignores late reads from an account that has been unmounted', async () => {
  let finish!: (value: api.PolicySnapshot) => void;
  vi.mocked(api.getPolicy).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }));
  const {rerender} = render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>);
  tenant = {...tenant, tenantName: '新账号租户'};
  rerender(<PolicyEditor userId="bob" scope={policyScope} kind="tenant"/>);
  await screen.findByText('新账号租户 · 个人租户政策');
  await act(async () => finish({kind: 'tenant', value: tenantPolicyView}));
  expect(screen.queryByText('个人租户名称 · 个人租户政策')).not.toBeInTheDocument();
});

it('does not repeat an acknowledged save when only the post-save read failed', async () => {
  vi.mocked(api.getPolicy).mockResolvedValueOnce({
    kind: 'tenant',
    value: tenant
  }).mockRejectedValueOnce(new TypeError('network'));
  render(<PolicyEditor userId="alice" scope={policyScope} kind="tenant"/>);
  fireEvent.click(await draft());
  await screen.findByText('政策已确认保存，但最新政策读取失败，请重新读取后继续。');
  expect(screen.queryByRole('button', {name: '重试确认原政策操作'})).not.toBeInTheDocument();
  expect(api.executePolicyCommand).toHaveBeenCalledTimes(1);
});

it('shows saved conflicts and checks a draft without writing when the tenant ceiling blocks it', async () => {
  workspace.effect = {effective: {...policyRules, allowedProviders: []}, limitedFields: ['allowedProviders']};
  vi.mocked(api.previewWorkspacePolicy).mockResolvedValueOnce({
    scope: policyScope, workspaceVersion: 9, workspacePolicyVersion: 4, tenantPolicyVersion: 3,
    proposed: {effective: policyRules, limitedFields: ['personalCredentialsAllowed', 'allowedProviders']}
  });
  render(<PolicyEditor userId="alice" scope={policyScope} kind="workspace"/>);
  await screen.findByText('已保存的空间设置超出当前租户上限');
  await draft('workspace');
  fireEvent.click(screen.getByRole('button', {name: '预览草稿效果'}));
  expect(await screen.findByText('草稿超出当前租户上限')).toBeVisible();
  expect(screen.getByText('个人凭据、供应商')).toBeVisible();
  expect(screen.getByRole('button', {name: '保存空间政策'})).toBeDisabled();
  expect(api.executePolicyCommand).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('switch', {name: '允许个人凭据'}));
  expect(screen.queryByText('草稿超出当前租户上限')).not.toBeInTheDocument();
});

it('keeps the draft and blocks writes when the preview basis changes or the authority cannot be read', async () => {
  vi.mocked(api.previewWorkspacePolicy).mockRejectedValueOnce(new TypeError('network'))
    .mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  render(<PolicyEditor userId="alice" scope={policyScope} kind="workspace"/>);
  const save = await draft('workspace');
  fireEvent.click(save);
  expect(await screen.findByText('草稿政策效果未知，未提交保存。请重试预览或重新读取政策。')).toBeVisible();
  fireEvent.click(save);
  await screen.findByText('政策或父记录版本已变化，草稿已保留。请读取最新版本并核对差异后再保存。');
  expect(save).toBeDisabled();
  expect(screen.getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
  expect(api.executePolicyCommand).not.toHaveBeenCalled();
});
