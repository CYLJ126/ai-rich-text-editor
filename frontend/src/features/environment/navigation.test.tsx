import React from 'react';
import {cleanup, render, screen} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import EnvironmentPage from '@/pages/Environment';
import WorkspaceConfigurationPage from '@/pages/Environment/Workspaces';

const session = vi.hoisted(() => ({user: null as { id: string; menus: string[] } | null}));
vi.mock('@umijs/max', () => ({
  history: {
    location: {search: ''}, listen: vi.fn(() => () => {
    }), push: vi.fn(), block: vi.fn(() => () => {
    })
  },
  useModel: () => ({initialState: {currentUser: session.user}}),
  Link: ({to, children}: { to: string; children: React.ReactNode }) => <a href={to}>{children}</a>,
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceDirectory: () => ({current: null, select: vi.fn()}),
  registerWorkspaceGuard: vi.fn(() => () => {
  })
}));
vi.mock('@/features/workspace/WorkspacePicker', () => ({
  default: ({userId, presentation}: { userId: string; presentation: string }) =>
    <div>已有空间管理：{userId}／{presentation}</div>,
}));
beforeEach(() => {
  session.user = {id: 'operator', menus: []};
});
afterEach(cleanup);

it('only links configuration entries assigned to the signed-in account', () => {
  session.user = {id: 'operator', menus: ['Environment', 'EnvironmentTenants', 'EnvironmentModels']};
  render(<EnvironmentPage/>);
  expect(screen.getByRole('link', {name: '进入租户配置'})).toHaveAttribute('href', '/Environment/Tenants');
  expect(screen.getByRole('link', {name: '进入模型配置'})).toHaveAttribute('href', '/Environment/Models');
  expect(screen.queryByRole('link', {name: '进入工作空间配置'})).not.toBeInTheDocument();
  expect(screen.queryByRole('link', {name: '进入工具配置'})).not.toBeInTheDocument();
});

it('explains missing menu assignments without adding unavailable operations', () => {
  render(<EnvironmentPage/>);
  expect(screen.getByText('暂无已分配的配置入口，请联系管理员分配菜单。')).toBeInTheDocument();
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
});

it('mounts existing workspace management directly without preparing models or AI', () => {
  render(<WorkspaceConfigurationPage/>);
  expect(screen.getByText('已有空间管理：operator／inline')).toBeInTheDocument();
});

it('does not mount workspace management before login', () => {
  session.user = null;
  render(<WorkspaceConfigurationPage/>);
  expect(screen.getByText('请登录后管理工作空间。')).toBeInTheDocument();
  expect(screen.queryByText(/已有空间管理/)).not.toBeInTheDocument();
});
