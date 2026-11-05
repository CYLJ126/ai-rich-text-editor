import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/policy';
import * as coordination from '@/features/workspace/useWorkspace';
import type {WorkspaceSummary} from '@/services/platform/workspace';
import {getWorkspace} from '@/services/platform/workspace';
import {savePolicyOperation} from '@/features/workspace/policyRecovery';
import {PolicySettings} from './index';
import {policyRules, policyScope, tenantPolicyView, workspacePolicyView} from '@root/tests/fixtures/policy';

vi.mock('@umijs/max', () => ({request: vi.fn(), useModel: vi.fn(), history: {push: vi.fn()}}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceManagement: () => {
    React.useSyncExternalStore(subscribe, () => revision);
    return directory;
  },
  useCurrentWorkspace: () => directory.current,
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
  protectWorkspaceChange: vi.fn((_user, _scope, proceed) => proceed()),
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(),
  getWorkspace: vi.fn()
}));
vi.mock('@/features/workspace/usePolicyDirectory', () => ({usePolicyDirectory: () => candidates}));
vi.mock('@/services/platform/policy', async original => ({
  ...(await original<typeof import('@/services/platform/policy')>()), getPolicy: vi.fn(), executePolicyCommand: vi.fn(),
}));
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
let revision = 0;
let directory: Pick<ReturnType<typeof coordination.useWorkspaceDirectory>, 'current' | 'preference' | 'selecting'
  | 'saving' | 'loading' | 'pending' | 'recoveryBlocked' | 'unsafeToLeave' | 'error' | 'warning' | 'select' | 'load'>;
const secondScope = {...policyScope, workspaceId: 'second-space'};
const otherScope = {tenantId: 'other-tenant', workspaceId: 'other-space'};
const personalScope = {tenantId: 'my-personal-tenant', workspaceId: 'my-default-space'};
const summary = (scope: typeof policyScope, name: string): WorkspaceSummary => ({
  scope, name, state: 0, currentRole: 0, version: 9, defaultWorkspace: scope === personalScope,
});
const records = [summary(policyScope, '协作空间'), summary(secondScope, '第二空间'),
  summary(otherScope, '其他租户空间'), summary(personalScope, '个人默认空间')];
let candidates: {
  records: WorkspaceSummary[]; exhausted: boolean; expired: boolean; loading: boolean; error: string;
  hasMore: boolean; reload: ReturnType<typeof vi.fn>; more: ReturnType<typeof vi.fn>
};

function changed() {
  revision++;
  listeners.forEach(listener => {
    listener();
  });
}

async function choose(name: string, label: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', {name}));
  fireEvent.click(await screen.findByText(label, {selector: '.ant-select-item-option-content'}));
}
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  revision = 0;
  directory = {
    current: records[0],
    preference: {defaultScope: personalScope, startupScope: null, lastScope: null, favorites: [], version: 0},
    selecting: false,
    saving: false,
    loading: false,
    pending: null,
    recoveryBlocked: false,
    unsafeToLeave: false,
    error: null,
    warning: null,
    load: vi.fn(async () => {
    }),
    select: vi.fn(async scope => {
      directory.current = records.find(item => item.scope.tenantId === scope.tenantId && item.scope.workspaceId === scope.workspaceId) ?? null;
      changed();
      return !!directory.current;
    }),
  };
  candidates = {
    records, exhausted: true, expired: false, loading: false, error: '', hasMore: false,
    reload: vi.fn(), more: vi.fn()
  };
  vi.mocked(coordination.registerWorkspaceGuard).mockImplementation(() => () => {
  });
  vi.mocked(coordination.protectWorkspaceChange).mockImplementation((_user, _scope, proceed) => proceed());
  vi.mocked(api.getPolicy).mockImplementation(async (kind, scope) => kind === 'tenant'
    ? {
      kind, value: {
        ...structuredClone(tenantPolicyView),
        tenantName: scope.tenantId === policyScope.tenantId
          ? tenantPolicyView.tenantName : scope.tenantId,
        policy: {...structuredClone(tenantPolicyView.policy), tenantId: scope.tenantId}
      }
    }
    : {kind,
      value: {
        ...structuredClone(workspacePolicyView),
        policy: {...structuredClone(workspacePolicyView.policy), scope: scope as typeof policyScope}
      }
    });
  vi.mocked(getWorkspace).mockImplementation(async scope => ({body: {data: records.find(item => item.scope.tenantId === scope.tenantId && item.scope.workspaceId === scope.workspaceId)}}) as never);
  vi.mocked(api.executePolicyCommand).mockResolvedValue(undefined);
});
afterEach(cleanup);

it('offers the personal-tenant shortcut through validated selection without mounting space management', async () => {
  render(<PolicySettings userId="page-user"/>);
  await screen.findByText('个人租户名称 · 个人租户政策');
  const workspaceButton = screen.getByRole('button', {name: '前往工作空间配置'});
  const modelButton = screen.getByRole('button', {name: '前往模型配置'});
  expect(workspaceButton).not.toHaveAttribute('href');
  expect(modelButton).not.toHaveAttribute('href');
  fireEvent.click(workspaceButton);
  expect(history.push).toHaveBeenNthCalledWith(1, '/Environment/Workspaces');
  fireEvent.click(modelButton);
  expect(history.push).toHaveBeenNthCalledWith(2, '/Environment/Models');
  fireEvent.click(screen.getByRole('button', {name: '我的个人租户政策'}));
  await waitFor(() => expect(getWorkspace).toHaveBeenCalledWith(personalScope, expect.any(Object)));
  expect(directory.select).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', {name: '新建工作空间'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '选择工作空间'})).not.toBeInTheDocument();
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

it('offers discard or save before navigation without switching the public workspace', async () => {
  render(<PolicySettings userId="page-user"/>);
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(screen.getByRole('button', {name: '前往工作空间配置'}));
  expect(history.push).not.toHaveBeenCalled();
  expect(screen.getByRole('button', {name: '保存并继续'})).toBeEnabled();
  fireEvent.click(screen.getByRole('button', {name: '放弃修改并继续'}));
  await waitFor(() => expect(history.push).toHaveBeenCalledWith('/Environment/Workspaces'));
  expect(api.executePolicyCommand).not.toHaveBeenCalled();
  expect(directory.select).not.toHaveBeenCalled();
});

it('waits for a confirmed policy save before continuing navigation', async () => {
  let finish!: () => void;
  vi.mocked(api.executePolicyCommand).mockImplementation(() => new Promise(resolve => {
    finish = resolve;
  }));
  render(<PolicySettings userId="page-user"/>);
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(screen.getByRole('button', {name: '前往模型配置'}));
  fireEvent.click(screen.getByRole('button', {name: '保存并继续'}));
  await waitFor(() => expect(api.executePolicyCommand).toHaveBeenCalledOnce());
  expect(history.push).not.toHaveBeenCalled();
  await act(async () => finish());
  await waitFor(() => expect(history.push).toHaveBeenCalledWith('/Environment/Models'));
  expect(directory.select).not.toHaveBeenCalled();
});

it('retains a policy conflict and does not continue or invent a new operation', async () => {
  vi.mocked(api.executePolicyCommand).mockRejectedValue(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  render(<PolicySettings userId="page-user"/>);
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  fireEvent.click(screen.getByRole('button', {name: '前往模型配置'}));
  fireEvent.click(screen.getByRole('button', {name: '保存并继续'}));
  await screen.findByText('“租户政策”尚未处理完成，请核对原页面提示。');
  expect(history.push).not.toHaveBeenCalled();
  expect(api.executePolicyCommand).toHaveBeenCalledOnce();
  expect(within(screen.getByLabelText('租户政策编辑')).getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
});

it('rechecks local drafts when a target read completes after editing began', async () => {
  let finish!: (value: Awaited<ReturnType<typeof getWorkspace>>) => void;
  vi.mocked(getWorkspace).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }));
  render(<PolicySettings userId="page-user"/>);
  await screen.findByText('个人租户名称 · 个人租户政策');
  await choose('选择租户', '租户（other-tenant）');
  fireEvent.click(screen.getByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  await act(async () => finish({body: {data: records[2]}} as never));
  expect(screen.getByText('处理当前配置后继续')).toBeInTheDocument();
  expect(screen.getByText('个人租户名称 · 个人租户政策')).toBeInTheDocument();
  expect(directory.select).not.toHaveBeenCalled();
});

it('does not invent a policy scope or add initialization controls when no space is available', () => {
  directory.current = null;
  candidates.records = [];
  render(<PolicySettings userId="page-user"/>);
  expect(screen.getByText('尚未选择可访问的租户。请从上方选择，或前往工作空间配置检查初始化及访问资格。')).toBeInTheDocument();
  expect(api.getPolicy).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', {name: '初始化个人默认空间'})).not.toBeInTheDocument();
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

it('switches tenants using a real accessible representative and queries the tenant without a workspace id', async () => {
  render(<PolicySettings userId="page-user"/>);
  await choose('选择租户', '租户（other-tenant）');
  await screen.findByText('other-tenant · 个人租户政策');
  expect(getWorkspace).toHaveBeenCalledWith(otherScope, expect.any(Object));
  expect(directory.current?.scope).toEqual(policyScope);
  expect(directory.select).not.toHaveBeenCalled();
  expect(api.getPolicy).toHaveBeenCalledWith('tenant', {tenantId: otherScope.tenantId}, expect.any(Object));
  expect(api.getPolicy).toHaveBeenCalledWith('workspace', otherScope, expect.any(Object));
});

it('limits the workspace dropdown to the selected tenant and preserves its tenant editor on a space switch', async () => {
  render(<PolicySettings userId="page-user"/>);
  await screen.findByText('个人租户名称 · 个人租户政策');
  fireEvent.click(screen.getByRole('tab', {name: '空间政策'}));
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '选择政策工作空间'}));
  expect(await screen.findByText('第二空间（second-space）', {selector: '.ant-select-item-option-content'})).toBeInTheDocument();
  expect(screen.queryByText('其他租户空间（other-space）')).not.toBeInTheDocument();
  fireEvent.click(screen.getByText('第二空间（second-space）', {selector: '.ant-select-item-option-content'}));
  await screen.findByText('第二空间 · 空间政策');
  expect(getWorkspace).toHaveBeenCalledWith(secondScope, expect.any(Object));
  expect(directory.select).not.toHaveBeenCalled();
  expect(vi.mocked(api.getPolicy).mock.calls.filter(([kind]) => kind === 'tenant')).toHaveLength(1);
});

it('offers pagination without treating a partial directory as complete', () => {
  candidates.exhausted = false;
  candidates.hasMore = true;
  render(<PolicySettings userId="page-user"/>);
  fireEvent.click(screen.getByRole('button', {name: '加载更多租户和空间'}));
  expect(candidates.more).toHaveBeenCalledOnce();
  expect(screen.getByText(/目录尚未加载完毕/)).toBeInTheDocument();
});

it('discards a late tenant response after the target changes', async () => {
  const original = vi.mocked(api.getPolicy).getMockImplementation();
  let finish!: (value: api.PolicySnapshot) => void;
  vi.mocked(api.getPolicy).mockImplementation((kind, scope, options) => kind === 'tenant' && scope.tenantId === policyScope.tenantId
    ? new Promise(resolve => {
      finish = resolve;
    }) : original?.(kind, scope, options) as Promise<api.PolicySnapshot>);
  render(<PolicySettings userId="page-user"/>);
  await choose('选择租户', '租户（other-tenant）');
  await screen.findByText('other-tenant · 个人租户政策');
  await act(async () => finish({kind: 'tenant', value: {...tenantPolicyView, tenantName: '迟到的旧租户'}}));
  expect(screen.queryByText(/迟到的旧租户/)).not.toBeInTheDocument();
});

it('retains a pending tenant operation and retries with its original key while selection stays locked', async () => {
  const command: api.PolicyCommand = {
    kind: 'tenant', request: {
      tenantId: policyScope.tenantId,
      expectedTenantVersion: 8, expectedPolicyVersion: 3, rules: policyRules, membersMayCreateWorkspace: true
    }
  };
  savePolicyOperation('page-user', policyScope, 'tenant', {schemaVersion: 1, key: 'original-key', command});
  vi.mocked(api.executePolicyCommand).mockRejectedValue(new TypeError('network'));
  render(<PolicySettings userId="page-user"/>);
  await screen.findByRole('button', {name: '重试确认原政策操作'});
  expect(api.executePolicyCommand).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原政策操作'}));
  await waitFor(() => expect(api.executePolicyCommand).toHaveBeenCalledWith(command, 'original-key'));
  await choose('选择租户', '租户（other-tenant）');
  expect(screen.getByRole('button', {name: '保存并继续'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '放弃修改并继续'})).toBeDisabled();
  expect(getWorkspace).not.toHaveBeenCalled();
});
