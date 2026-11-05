import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {AiApiError} from '@/services/arte-ai/request';
import {browseAccessibleTenants, getTenant, type TenantDetail, type TenantSummary} from '@/services/platform/tenant';
import {browseTenantWorkspaces} from '@/services/platform/workspace';
import {retainWorkspaceSelection} from '@/features/workspace/useWorkspace';
import * as policy from '@/services/platform/policy';
import {savePolicyOperation} from '@/features/workspace/policyRecovery';
import {policyRules, tenantPolicyView} from '@root/tests/fixtures/policy';
import Page from './index';

vi.mock('@umijs/max', () => ({
  request: vi.fn(), useModel: () => ({initialState: {currentUser: {id: 'alice'}}}),
  history: {push: vi.fn(), replace: vi.fn()}
}));
vi.mock('@/services/platform/tenant', () => ({browseAccessibleTenants: vi.fn(), getTenant: vi.fn()}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(), browseTenantWorkspaces: vi.fn()
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  registerWorkspaceGuard: () => () => {
  },
  retainWorkspaceSelection: vi.fn(),
}));
vi.mock('@/services/platform/policy', async original => ({
  ...await original<typeof import('@/services/platform/policy')>(), getPolicy: vi.fn(), executePolicyCommand: vi.fn(),
}));

const team: TenantSummary = {
  id: 'team',
  name: '团队租户',
  type: 1,
  state: 0,
  version: 1,
  currentRole: 2,
  isPersonal: false
};
const admin: TenantSummary = {...team, id: 'admin', name: '治理租户', currentRole: 1};
const personal: TenantSummary = {...team, id: 'me', name: '个人租户甲', type: 0, currentRole: 0, isPersonal: true};
const space = {
  scope: {tenantId: team.id, workspaceId: 'a'},
  name: '项目空间',
  currentRole: 2,
  state: 0,
  version: 1,
  defaultWorkspace: false
};
const response = <T, >(data: T) => ({body: {data}}) as never;
const page = (records: unknown[], nextCursor: string | null = null) => response({
  records,
  nextCursor,
  exhausted: nextCursor === null
});
const details = (value: TenantSummary): TenantDetail => ({
  ...value, viewLevel: value.currentRole === 2 ? 'BASIC' : 'GOVERNANCE',
  actions: value.currentRole === 2 ? ['tenant:read'] : ['tenant:read', 'tenant:policy:write'],
});
const select = vi.fn();
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  window.history.replaceState(null, '', '/Environment/Tenants');
  vi.mocked(browseAccessibleTenants).mockResolvedValue(page([team, admin]));
  vi.mocked(getTenant).mockImplementation(async id => {
    const tenant = [team, admin, personal].find(item => item.id === id);
    if (!tenant) throw new Error(`Unexpected fixture tenant ${id}`);
    return response(details(tenant));
  });
  vi.mocked(browseTenantWorkspaces).mockResolvedValue(page([]));
  vi.mocked(retainWorkspaceSelection).mockReturnValue({select, release: vi.fn()});
  vi.mocked(policy.getPolicy).mockImplementation(async (_kind, scope) => ({
    kind: 'tenant',
    value: {
      ...structuredClone(tenantPolicyView),
      policy: {...structuredClone(tenantPolicyView.policy), tenantId: scope.tenantId}
    },
  }));
});
afterEach(cleanup);

const choose = async (name: string) => fireEvent.click(await screen.findByRole('button', {name: `选择租户 ${name}`}));
const tab = async (name: string) => fireEvent.click(await screen.findByRole('tab', {name}));

it('loads an independent tenant directory and shows a member overview for a tenant with no spaces', async () => {
  render(<Page/>);
  await screen.findByRole('button', {name: '选择租户 团队租户'});
  expect(getTenant).not.toHaveBeenCalled();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  await choose(team.name);
  expect(await screen.findByRole('tab', {name: '概览'})).toHaveAttribute('aria-selected', 'true');
  expect(getTenant).toHaveBeenCalledWith('team', {signal: expect.any(AbortSignal)});
  expect(browseTenantWorkspaces).not.toHaveBeenCalled();
  await tab('租户政策');
  expect(screen.getByText('租户政策由负责人和管理员维护。')).toBeInTheDocument();
  expect(policy.getPolicy).not.toHaveBeenCalled();
  await tab('工作空间');
  expect(await screen.findByText('此租户暂无你可访问的工作空间。')).toBeInTheDocument();
  expect(browseTenantWorkspaces).toHaveBeenCalledWith('team', null, '', {signal: expect.any(AbortSignal)});
  expect(screen.queryByRole('tab', {name: '成员'})).not.toBeInTheDocument();
});

it('sends search to the server, continues an empty page and recovers an expired cursor', async () => {
  vi.mocked(browseAccessibleTenants)
    .mockResolvedValueOnce(page([team]))
    .mockResolvedValueOnce(page([], 'continuation'))
    .mockRejectedValueOnce(new AiApiError(410, {code: 'PLATFORM_CURSOR_EXPIRED'}))
    .mockResolvedValueOnce(page([admin]));
  render(<Page/>);
  await screen.findByRole('button', {name: '选择租户 团队租户'});
  fireEvent.change(screen.getByRole('searchbox', {name: '搜索租户'}), {target: {value: '治理'}});
  fireEvent.click(screen.getByRole('button', {name: /^搜\s*索$/}));
  fireEvent.click(await screen.findByRole('button', {name: '继续加载租户'}));
  expect(await screen.findByText('目录已过期，请从第一页重新读取。')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '刷新租户目录'}));
  expect(await screen.findByRole('button', {name: '选择租户 治理租户'})).toBeInTheDocument();
  expect(vi.mocked(browseAccessibleTenants).mock.calls.map(([query]) => [query?.search, query?.cursor]))
    .toEqual([['', null], ['治理', null], ['治理', 'continuation'], ['治理', null]]);
});

it('finds the account’s personal tenant past an empty filtered page, then verifies its detail', async () => {
  vi.mocked(browseAccessibleTenants)
    .mockResolvedValueOnce(page([team]))
    .mockResolvedValueOnce(page([], 'personal-next'))
    .mockResolvedValueOnce(page([personal]));
  render(<Page/>);
  fireEvent.click(screen.getByRole('button', {name: '定位我的个人租户'}));
  expect(await screen.findByText('正在配置：个人租户甲')).toBeInTheDocument();
  expect(getTenant).toHaveBeenCalledWith('me', expect.any(Object));
  expect(vi.mocked(browseAccessibleTenants).mock.calls.slice(1).map(([query]) => [query?.type, query?.cursor]))
    .toEqual([[0, null], [0, 'personal-next']]);
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
});

it('separates scoped workspace configuration from explicit entering', async () => {
  vi.mocked(browseTenantWorkspaces).mockResolvedValue(page([space]));
  select.mockImplementation(async (_scope, onSelected) => {
    onSelected();
    return true;
  });
  render(<Page/>);
  await choose(team.name);
  await tab('工作空间');
  fireEvent.click(await screen.findByRole('button', {name: '配置 项目空间'}));
  expect(history.push).toHaveBeenCalledWith('/Environment/Workspaces?account=alice&tenantId=team&workspaceId=a&tab=overview');
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '进入此空间 项目空间'}));
  expect(select).toHaveBeenCalledWith(space.scope, expect.any(Function));
  expect(await screen.findByText('已进入「项目空间」，聊天使用空间已切换。')).toBeInTheDocument();
});

it('rejects stale tenant reads and treats loss of access as distinct from an empty directory', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof getTenant>>) => void;
  vi.mocked(getTenant).mockImplementationOnce(() => new Promise(done => {
    resolve = done;
  }))
    .mockRejectedValueOnce(new AiApiError(404, {code: 'PLATFORM_NOT_FOUND'}));
  render(<Page/>);
  await choose(team.name);
  await choose(admin.name);
  expect(await screen.findByText('当前配置对象不可访问，访问资格可能已变化。')).toBeInTheDocument();
  await act(async () => resolve(response(details(team))));
  expect(screen.queryByText('正在配置：团队租户')).not.toBeInTheDocument();
  expect(policy.getPolicy).not.toHaveBeenCalled();
});

it('preserves a policy draft across tabs and guards changing tenants', async () => {
  render(<Page/>);
  await choose(admin.name);
  await tab('租户政策');
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  await tab('概览');
  await choose(team.name);
  expect(screen.getByText('处理当前配置后继续')).toBeInTheDocument();
  expect(getTenant).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', {name: '放弃修改并继续'}));
  expect(await screen.findByText('正在配置：团队租户')).toBeInTheDocument();
  expect(policy.executePolicyCommand).not.toHaveBeenCalled();
});

it('restores a policy deep link and confirms a pending request with its original key', async () => {
  window.history.replaceState(null, '', '/Environment/Tenants?account=alice&tenantId=admin&tab=policy');
  const command: policy.PolicyCommand = {
    kind: 'tenant', request: {
      tenantId: admin.id,
      expectedTenantVersion: 8,
      expectedPolicyVersion: 3,
      rules: policyRules,
      membersMayCreateWorkspace: true
    }
  };
  savePolicyOperation('alice', {tenantId: admin.id}, 'tenant', {schemaVersion: 1, key: 'original-key', command});
  vi.mocked(policy.executePolicyCommand).mockRejectedValue(new TypeError('offline'));
  render(<Page/>);
  const retry = await screen.findByRole('button', {name: '重试确认原政策操作'});
  expect(policy.executePolicyCommand).not.toHaveBeenCalled();
  fireEvent.click(retry);
  await waitFor(() => expect(policy.executePolicyCommand).toHaveBeenCalledWith(command, 'original-key'));
  await choose(team.name);
  expect(screen.getByRole('button', {name: '保存并继续'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '放弃修改并继续'})).toBeDisabled();
  expect(getTenant).toHaveBeenCalledTimes(1);
});
