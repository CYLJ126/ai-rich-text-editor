import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import ToolsPage from './index';
import {getWorkspaceAi} from '@/services/platform/ai';

const state = vi.hoisted(() => ({
  workspace: {
    scope: {tenantId: 'tenant', workspaceId: 'space'}, name: '空间', state: 0, currentRole: 0, version: 0,
  }
}));
vi.mock('@umijs/max', () => ({
  history: {push: vi.fn()},
  useModel: () => ({initialState: {currentUser: {id: 'user'}}})
}));
vi.mock('@/features/workspace/useWorkspace', () => ({useCurrentWorkspace: () => state.workspace}));
vi.mock('@/features/workspace/WorkspacePicker', () => ({default: () => <p>空间管理</p>}));
vi.mock('@/features/workspace/WorkspaceQuickSelect', () => ({default: () => <p>空间切换</p>}));
vi.mock('@/features/aitool/ToolWorkspace', () => ({default: () => <p>工具消费者</p>}));
vi.mock('@/features/ainew/WorkspaceAiSettings', () => ({default: () => <p>准备 AI 配置</p>}));
vi.mock('@/services/platform/ai', () => ({getWorkspaceAi: vi.fn()}));

beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

it('prepares AI on direct entry to a new space before mounting tool consumers', async () => {
  let finish!: () => void;
  vi.mocked(getWorkspaceAi).mockImplementation(() => new Promise(resolve => {
    finish = () => resolve({body: {data: {needsImport: false}}} as never);
  }));
  render(<ToolsPage/>);
  expect(screen.getByText('空间切换')).toBeInTheDocument();
  expect(screen.queryByText('工具消费者')).not.toBeInTheDocument();
  expect(getWorkspaceAi).toHaveBeenCalledWith(state.workspace.scope, expect.objectContaining({signal: expect.any(AbortSignal)}));
  finish();
  expect(await screen.findByText('工具消费者')).toBeInTheDocument();
});

it('keeps space navigation available and retries AI preparation after failure', async () => {
  vi.mocked(getWorkspaceAi).mockRejectedValueOnce(new TypeError('network'))
    .mockResolvedValueOnce({body: {data: {needsImport: false}}} as never);
  render(<ToolsPage/>);
  fireEvent.click(await screen.findByRole('button', {name: '重新检查'}));
  expect(await screen.findByText('工具消费者')).toBeInTheDocument();
  expect(screen.getByText('空间切换')).toBeInTheDocument();
  await waitFor(() => expect(getWorkspaceAi).toHaveBeenCalledTimes(2));
});

it('shows initial AI configuration when the space has no published template', async () => {
  vi.mocked(getWorkspaceAi).mockResolvedValue({body: {data: {needsImport: true}}} as never);
  render(<ToolsPage/>);
  expect(await screen.findByText('平台审核目录尚未准备，请联系平台配置管理员。')).toBeInTheDocument();
  expect(screen.queryByText('准备 AI 配置')).not.toBeInTheDocument();
  expect(screen.queryByText('工具消费者')).not.toBeInTheDocument();
  expect(screen.getByText('空间切换')).toBeInTheDocument();
});
