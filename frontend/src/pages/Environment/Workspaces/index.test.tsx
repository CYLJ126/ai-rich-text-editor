import React, {useState} from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {getWorkspace} from '@/services/platform/workspace';
import {browseAccessibleTenants} from '@/services/platform/tenant';
import {retainWorkspaceSelection} from '@/features/workspace/useWorkspace';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import type WorkspaceDetailForm from '@/features/workspace/WorkspaceDetailForm';
import Page from './index';

vi.mock('@umijs/max', () => ({
  useModel: () => ({initialState: {currentUser: {id: 'alice'}}}), history: {
    block: vi.fn(() => () => {
    }), listen: vi.fn(() => () => {
    }), push: vi.fn()
  }
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceManagement: () => directory,
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
  retainWorkspaceSelection: vi.fn(),
}));
vi.mock('@/services/platform/tenant', () => ({browseAccessibleTenants: vi.fn()}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(), getWorkspace: vi.fn()
}));
vi.mock('@/features/workspace/WorkspaceDetailForm', () => ({
  default: function Detail({
                             scope,
                             tab,
                             tenants,
                             onSaved,
                             onUnsafeChange,
                             onCancel,
                             preferences
                           }: React.ComponentProps<typeof WorkspaceDetailForm>) {
    const [dirty, setDirty] = useState(false);
    useConfigurationDraft({group: 'workspace-metadata', label: '空间基本信息', status: dirty ? 'dirty' : 'clean'});
    return <section aria-label="配置详情测试">
      {scope ? `编辑目标 ${scope.workspaceId} · ${tab}` : '新增空间表单'}
      {!scope && tenants?.map(tenant => <div key={tenant.value}>{tenant.label}</div>)}
      <button type="button" onClick={() => {
        setDirty(true);
        onUnsafeChange?.(true);
      }}>模拟修改
      </button>
      <button type="button" onClick={() => {
        setDirty(false);
        onUnsafeChange?.(false);
        onSaved?.({...space, scope: {...space.scope, workspaceId: scope?.workspaceId ?? 'C'}});
      }}>模拟保存
      </button>
      {onCancel && <button type="button" onClick={onCancel}>取消新增</button>}
      {preferences}
    </section>;
  }
}));
const space = {
  scope: {tenantId: 't', workspaceId: 'B'},
  name: '空间 B',
  state: 0 as const,
  currentRole: 0 as const,
  version: 1
};
const pref = {defaultScope: space.scope, startupScope: space.scope, lastScope: null, favorites: [], version: 0};
let directory: ReturnType<typeof import('@/features/workspace/useWorkspace').useWorkspaceManagement>;
const select = vi.fn();
beforeEach(() => {
  vi.resetAllMocks();
  window.history.replaceState(null, '', '/Environment/Workspaces?tenantId=t&workspaceId=B&returnTo=chat');
  directory = {
    records: [{...space, defaultWorkspace: false}], preference: pref, search: '', loaded: true,
    load: vi.fn(), loadMore: vi.fn(), updatePreference: vi.fn(), favorite: vi.fn(() => false)
  } as unknown as typeof directory;
  vi.mocked(retainWorkspaceSelection).mockReturnValue({select, release: vi.fn()});
  vi.mocked(browseAccessibleTenants).mockResolvedValue({
    body: {
      data: {
        records: [{id: 'org-without-space', name: '尚无空间的组织'}], nextCursor: null, exhausted: true,
      }
    }
  } as never);
});
afterEach(cleanup);

const choose = () => fireEvent.click(screen.getByRole('button', {name: '配置 空间 B'}));

it('opens an overview only after selecting a card when no tab is specified', () => {
  const page = render(<Page/>);
  expect(screen.getByText('从左侧选择工作空间，或点击列表上方的新建按钮。')).toBeInTheDocument();
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
  choose();
  expect(screen.getByText('编辑目标 B · overview')).toBeInTheDocument();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  page.unmount();
  render(<Page/>);
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
});

it('protects dirty details before entering the selected workspace and reports only committed selection', async () => {
  select.mockImplementation(async (_scope, commit) => {
    commit();
    return true;
  });
  render(<Page/>);
  choose();
  fireEvent.click(screen.getByRole('button', {name: '模拟修改'}));
  fireEvent.click(screen.getByRole('button', {name: '进入此空间'}));
  expect(screen.getByText('处理当前配置后继续')).toBeInTheDocument();
  expect(select).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '留在本页'}));
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  fireEvent.click(screen.getByRole('button', {name: '进入此空间'}));
  expect(await screen.findByText('已进入此空间，聊天使用空间已切换。')).toBeInTheDocument();
  expect(select).toHaveBeenCalledWith(space.scope, expect.any(Function));
});

it('uses the independent tenant directory for creation and opens the created space locally', async () => {
  render(<Page/>);
  expect(browseAccessibleTenants).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '新建工作空间'}));
  expect(screen.getByText('新增空间表单')).toBeInTheDocument();
  expect(await screen.findByText('尚无空间的组织')).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '进入此空间'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  expect(screen.getByText('编辑目标 C · overview')).toBeInTheDocument();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  expect(directory.updatePreference).not.toHaveBeenCalled();
});

it('keeps search in the directory and startup settings under my preferences', () => {
  render(<Page/>);
  fireEvent.change(screen.getByLabelText('搜索工作空间'), {target: {value: '空间'}});
  fireEvent.click(screen.getByRole('button', {name: /^搜\s*索$/}));
  expect(directory.load).toHaveBeenCalledWith('空间');
  expect(screen.queryByRole('button', {name: '清除启动空间偏好'})).not.toBeInTheDocument();
  choose();
  expect(screen.getByText('我的偏好')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '清除启动空间偏好'}));
  expect(directory.updatePreference).toHaveBeenCalledWith({startupScope: null});
});

it.each(['ai', 'policy'])('validates an explicit %s link and opens the corresponding detail tab', async tab => {
  window.history.replaceState(null, '', `/Environment/Workspaces?account=alice&tenantId=t&workspaceId=B&tab=${tab}`);
  vi.mocked(getWorkspace).mockResolvedValue({body: {data: space}} as never);
  render(<Page/>);
  expect(await screen.findByText(`编辑目标 B · ${tab}`)).toBeInTheDocument();
  expect(getWorkspace).toHaveBeenCalledWith(space.scope, {signal: expect.any(AbortSignal)});
  expect(directory.updatePreference).not.toHaveBeenCalled();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
});

it('keeps the current usage space when revalidation fails', async () => {
  select.mockResolvedValue(false);
  render(<Page/>);
  choose();
  fireEvent.click(screen.getByRole('button', {name: '进入此空间'}));
  await waitFor(() => expect(screen.getByText(/未切换使用空间/)).toBeInTheDocument());
  expect(screen.queryByText('已进入此空间，聊天使用空间已切换。')).not.toBeInTheDocument();
});
