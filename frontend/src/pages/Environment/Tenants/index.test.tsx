import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {AiApiError} from '@/services/arte-ai/request';
import {
  browseAccessibleTenants,
  executeTenantCommand,
  getTenant,
  getTenantCommandReceipt,
  getTenantCreationOptions,
  type TenantDetail,
  type TenantReceipt,
  type TenantSummary
} from '@/services/platform/tenant';
import {browseTenantWorkspaces} from '@/services/platform/workspace';
import {retainWorkspaceSelection} from '@/features/workspace/useWorkspace';
import * as policy from '@/services/platform/policy';
import {savePolicyOperation} from '@/features/workspace/policyRecovery';
import {connectionOptions, policyRules, tenantPolicyView} from '@root/tests/fixtures/policy';
import Page from './index';

vi.mock('@umijs/max', () => ({
  request: vi.fn(), useModel: () => ({initialState: {currentUser: {id: 'alice'}}}),
  history: {push: vi.fn(), replace: vi.fn()}
}));
vi.mock('@/services/platform/tenant', async original => ({
  ...await original<typeof import('@/services/platform/tenant')>(),
  browseAccessibleTenants: vi.fn(),
  getTenant: vi.fn(),
  executeTenantCommand: vi.fn(),
  getTenantCommandReceipt: vi.fn(),
  getTenantCreationOptions: vi.fn()
}));
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
  getPolicyConnectionOptions: vi.fn(), previewWorkspacePolicy: vi.fn(),
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
  vi.mocked(policy.getPolicyConnectionOptions).mockImplementation(async (kind, scope) => connectionOptions(kind, scope));
  vi.mocked(policy.previewWorkspacePolicy).mockImplementation(async (basis, rules) => ({
    scope: basis.policy.scope, workspaceVersion: basis.workspaceVersion, workspacePolicyVersion: basis.policy.version,
    tenantPolicyVersion: basis.tenantPolicyVersion, proposed: {effective: rules, limitedFields: []}
  }));
  sessionStorage.clear();
  window.history.replaceState(null, '', '/Environment/Tenants');
  vi.mocked(getTenantCreationOptions).mockResolvedValue(response({
    available: false,
    organizationAllowed: false,
    reason: 'CAPABILITY_NOT_ENABLED'
  }));
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

it('requires explicit eligibility for both organization creation and a tenant rename', async () => {
  vi.mocked(getTenantCreationOptions).mockResolvedValue(response({
    available: true,
    organizationAllowed: false,
    reason: 'ACCESS_DENIED'
  }));
  render(<Page/>);
  await choose(admin.name);
  await screen.findByRole('tab', {name: '概览'});
  expect(screen.queryByRole('button', {name: '新建组织租户'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '编辑租户名称'})).not.toBeInTheDocument();
});

it('retains the rename on a version conflict, then saves against the newly read tenant version', async () => {
  const target: TenantDetail = {...details(admin), actions: ['tenant:read', 'tenant:policy:write', 'tenant:update']};
  vi.mocked(getTenant).mockResolvedValueOnce(response(target)).mockResolvedValue(response({
    ...target,
    version: 2,
    name: '并发修改'
  }));
  vi.mocked(executeTenantCommand).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}))
    .mockResolvedValueOnce({
      operationId: 'rename-tenant', status: 1, delivery: 0, resultVersion: 3, acceptedAt: 'now', completedAt: 'now',
      value: {id: admin.id, name: '新的名称', state: 0, type: 1, currentRole: 1, version: 3}
    });
  render(<Page/>);
  await choose(admin.name);
  fireEvent.click(await screen.findByRole('button', {name: '编辑租户名称'}));
  fireEvent.change(screen.getByLabelText('租户名称'), {target: {value: '新的名称'}});
  fireEvent.click(screen.getByRole('button', {name: '保存租户名称'}));
  expect(await screen.findByText('租户版本已变化。请读取最新版本，原名称草稿仍保留。')).toBeVisible();
  expect(screen.getByLabelText('租户名称')).toHaveValue('新的名称');
  expect(screen.getByRole('button', {name: '保存租户名称'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button', {name: '读取最新版本并保留名称'}));
  await waitFor(() => expect(screen.getByRole('button', {name: '保存租户名称'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '保存租户名称'}));
  await screen.findByText('租户操作已确认');
  expect(executeTenantCommand).toHaveBeenLastCalledWith({
    kind: 'update', request: {tenantId: admin.id, name: '新的名称', expectedTenantVersion: 2}
  }, expect.any(String));
  expect(vi.mocked(executeTenantCommand).mock.calls[0][1]).not.toBe(vi.mocked(executeTenantCommand).mock.calls[1][1]);
});

it('restores organization creation with its original key and opens only the new tenant overview', async () => {
  const created: TenantSummary = {...team, id: 'new-org', name: '新组织', currentRole: 0, version: 0};
  const {isPersonal: _isPersonal, ...value} = created;
  const receipt: TenantReceipt = {
    value, operationId: 'create-tenant', status: 1, delivery: 1, resultVersion: 0,
    acceptedAt: 'now', completedAt: 'now'
  };
  vi.mocked(getTenantCreationOptions).mockResolvedValue(response({
    available: true,
    organizationAllowed: true,
    reason: 'ALLOWED'
  }));
  vi.mocked(getTenant).mockResolvedValue(response(details(created)));
  vi.mocked(executeTenantCommand).mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(receipt);
  vi.mocked(getTenantCommandReceipt).mockResolvedValue(receipt);
  render(<Page/>);
  fireEvent.click(await screen.findByRole('button', {name: '新建组织租户'}));
  fireEvent.change(screen.getByLabelText('组织名称'), {target: {value: '新组织'}});
  fireEvent.click(screen.getByRole('button', {name: '创建组织租户'}));
  await screen.findByRole('button', {name: '确认原租户操作'});
  const original = vi.mocked(executeTenantCommand).mock.calls[0];
  cleanup();
  render(<Page/>);
  const retry = await screen.findByRole('button', {name: '确认原租户操作'});
  expect(executeTenantCommand).toHaveBeenCalledTimes(1);
  fireEvent.click(retry);
  expect(await screen.findByText('组织租户已建立。可在工作空间页签查看或创建空间。')).toBeVisible();
  expect(executeTenantCommand).toHaveBeenLastCalledWith(...original);
  expect(screen.getByRole('tab', {name: '概览'})).toHaveAttribute('aria-selected', 'true');
  expect(select).not.toHaveBeenCalled();
  expect(history.push).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '查询操作结果'}));
  expect(await screen.findByText('已核验原操作结果：新组织 · 租户版本 0')).toBeVisible();
  expect(getTenantCommandReceipt).toHaveBeenCalledWith('create-tenant', original[0], expect.anything());
  await tab('工作空间');
  expect(await screen.findByText('此租户暂无你可访问的工作空间。')).toBeVisible();
});

it('keeps an unsaved policy draft when the same tenant name is saved', async () => {
  const target: TenantDetail = {...details(admin), actions: ['tenant:read', 'tenant:policy:write', 'tenant:update']};
  const updated = {...target, name: '同一租户新名称', version: 2};
  vi.mocked(getTenant).mockResolvedValueOnce(response(target)).mockResolvedValue(response(updated));
  vi.mocked(executeTenantCommand).mockResolvedValue({
    operationId: 'same-tenant-name', status: 1, delivery: 0, resultVersion: 2, acceptedAt: 'now', completedAt: 'now',
    value: {id: admin.id, name: updated.name, state: 0, type: 1, currentRole: 1, version: 2}
  });
  render(<Page/>);
  await choose(admin.name);
  await tab('租户政策');
  fireEvent.click(await screen.findByRole('button', {name: '填入 DeepSeek 个人模型规则'}));
  await tab('概览');
  fireEvent.click(screen.getByRole('button', {name: '编辑租户名称'}));
  fireEvent.change(screen.getByLabelText('租户名称'), {target: {value: updated.name}});
  fireEvent.click(screen.getByRole('button', {name: '保存租户名称'}));
  await screen.findByText('正在配置：同一租户新名称');
  await tab('租户政策');
  expect(screen.getByRole('switch', {name: '允许个人凭据'})).toBeChecked();
  expect(screen.getByRole('button', {name: '保存租户政策'})).toBeDisabled();
  expect(policy.executePolicyCommand).not.toHaveBeenCalled();
});
