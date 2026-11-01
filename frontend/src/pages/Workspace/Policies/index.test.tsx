import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/policy';
import * as coordination from '@/features/workspace/useWorkspace';
import {PolicySettings} from './index';
import {policyScope, tenantPolicyView, workspacePolicyView} from '@root/tests/fixtures/policy';

vi.mock('@umijs/max', () => ({request: vi.fn(), useModel: vi.fn()}));
vi.mock('@/features/workspace/WorkspacePicker', () => ({default: () => <div>公共空间选择器</div>}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceDirectory: () => directory,
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
  protectWorkspaceChange: vi.fn((_user, _scope, proceed) => proceed()),
}));
vi.mock('@/services/platform/policy', async original => ({
  ...(await original<typeof import('@/services/platform/policy')>()), getPolicy: vi.fn(), executePolicyCommand: vi.fn(),
}));
type Directory = {
  current: { scope: typeof policyScope; name: string; state: number; currentRole: number; version: number } | null;
  preference: { defaultScope: typeof policyScope }; selecting: boolean; pending: null; recoveryBlocked: boolean;
  select: ReturnType<typeof vi.fn>
};
let directory: Directory;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  directory = {
    current: {scope: policyScope, name: '协作空间', state: 0, currentRole: 0, version: 9},
    preference: {defaultScope: {tenantId: 'my-personal-tenant', workspaceId: 'my-default-space'}},
    selecting: false, pending: null, recoveryBlocked: false, select: vi.fn(async () => true)
  };
  vi.mocked(coordination.registerWorkspaceGuard).mockImplementation(() => () => {
  });
  vi.mocked(coordination.protectWorkspaceChange).mockImplementation((_user, _scope, proceed) => proceed());
  vi.mocked(api.getPolicy).mockImplementation(async kind => kind === 'tenant'
    ? {kind, value: structuredClone(tenantPolicyView)} : {kind, value: structuredClone(workspacePolicyView)});
  vi.mocked(api.executePolicyCommand).mockResolvedValue(undefined);
});
afterEach(cleanup);

it('offers the personal-tenant entry through the validated default scope and selection guard', async () => {
  render(<PolicySettings userId="page-user"/>);
  await screen.findByText('个人租户名称 · 个人租户政策');
  fireEvent.click(screen.getByRole('button', {name: '我的个人租户政策'}));
  expect(coordination.protectWorkspaceChange).toHaveBeenCalledWith('page-user', directory.preference.defaultScope, expect.any(Function));
  expect(directory.select).toHaveBeenCalledWith(directory.preference.defaultScope);
});

it('allows the workspace tab independently of denied tenant governance', async () => {
  vi.mocked(api.getPolicy).mockImplementation(async kind => {
    if (kind === 'tenant') throw new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'});
    return {kind, value: workspacePolicyView};
  });
  render(<PolicySettings userId="page-user"/>);
  await screen.findByText('当前无权读取或修改租户政策；需要租户负责人或管理员的治理资格。空间政策可单独检查。');
  fireEvent.click(screen.getByRole('tab', {name: '空间政策'}));
  const space = within(screen.getByLabelText('空间政策编辑'));
  fireEvent.click(await space.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  expect(space.getByRole('button', {name: '保存空间政策'})).toBeEnabled();
  expect(api.executePolicyCommand).not.toHaveBeenCalled();
});

it('blocks picker scope changes while a policy draft is dirty', async () => {
  render(<PolicySettings userId="page-user"/>);
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  await waitFor(() => expect(screen.getByRole('button', {name: '我的个人租户政策'})).toBeDisabled());
  const proceed = vi.fn();
  const registered = vi.mocked(coordination.registerWorkspaceGuard).mock.calls.at(-1)!;
  registered[1]({tenantId: 'another', workspaceId: 'another-space'}, proceed);
  expect(proceed).not.toHaveBeenCalled();
  await screen.findByText('政策有未保存草稿或待确认操作，请先保存、放弃修改或确认原操作后再切换。');
});

it('does not invent a policy scope before public workspace initialization', () => {
  directory.current = null;
  render(<PolicySettings userId="page-user"/>);
  expect(screen.getByText('公共空间选择器')).toBeInTheDocument();
  expect(api.getPolicy).not.toHaveBeenCalled();
});

it('keeps an edited space draft when saving the tenant changes its ceiling', async () => {
  render(<PolicySettings userId="page-user"/>);
  fireEvent.click(screen.getByRole('tab', {name: '空间政策'}));
  const space = within(screen.getByLabelText('空间政策编辑'));
  fireEvent.click(await space.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(screen.getByRole('tab', {name: '租户政策（含个人）'}));
  const tenant = within(screen.getByLabelText('租户政策编辑'));
  fireEvent.click(await tenant.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(tenant.getByRole('button', {name: '保存租户政策'}));
  await tenant.findByText('政策已保存，已重新读取当前版本。');
  fireEvent.click(screen.getByRole('tab', {name: '空间政策'}));
  await space.findByText('租户政策上限已更新，当前草稿或原操作已保留，请重新核对后继续。');
  expect(space.getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
  expect(space.getByRole('button', {name: '保存空间政策'})).toBeDisabled();
  expect(api.executePolicyCommand).toHaveBeenCalledTimes(1);
});
