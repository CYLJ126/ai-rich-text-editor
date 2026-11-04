import React from 'react';
import {cleanup, fireEvent, render, screen} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import WorkspaceQuickSelect from './WorkspaceQuickSelect';
import type {useWorkspaceDirectory} from './useWorkspace';
import {protectWorkspaceChange} from './useWorkspace';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('./useWorkspace', () => ({
  useWorkspaceDirectory: () => directory,
  protectWorkspaceChange: vi.fn((_user, _scope, proceed) => proceed())
}));
const a = {tenantId: 'tenant', workspaceId: 'A'}, b = {...a, workspaceId: 'B'};
let directory: ReturnType<typeof useWorkspaceDirectory>;
beforeEach(() => {
  vi.clearAllMocks();
  directory = {
    current: {scope: a, name: '空间A'}, records: [{scope: a, name: '空间A'}, {scope: b, name: '空间B'}],
    exhausted: true, select: vi.fn(async () => true), load: vi.fn(), loadMore: vi.fn()
  } as unknown as typeof directory;
});
afterEach(cleanup);
it('only selects accessible spaces through the shared guard and offers no management entry', async () => {
  render(<WorkspaceQuickSelect userId="alice"/>);
  fireEvent.click(screen.getByRole('button', {name: '选择工作空间'}));
  expect(await screen.findByRole('menuitem', {name: '空间B'})).toBeInTheDocument();
  expect(screen.queryByRole('menuitem', {name: '管理工作空间'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('menuitem', {name: '空间B'}));
  expect(protectWorkspaceChange).toHaveBeenCalledWith('alice', b, expect.any(Function));
  expect(directory.select).toHaveBeenCalledWith(b);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
it('preserves directory continuation and exposes refresh after a read failure', async () => {
  directory = {...directory, exhausted: false, nextCursor: 'next', error: new Error('目录读取失败')};
  render(<WorkspaceQuickSelect userId="alice"/>);
  expect(screen.getByText('目录读取失败')).toBeVisible();
  fireEvent.click(screen.getByRole('button', {name: '选择工作空间'}));
  fireEvent.click(await screen.findByRole('menuitem', {name: '加载更多工作空间'}));
  expect(directory.loadMore).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', {name: '选择工作空间'}));
  fireEvent.click(await screen.findByRole('menuitem', {name: '重新读取工作空间'}));
  expect(directory.load).toHaveBeenCalledTimes(1);
});
it('does not select a space when the active page denies the switch', async () => {
  vi.mocked(protectWorkspaceChange).mockImplementationOnce(() => {
  });
  render(<WorkspaceQuickSelect userId="alice"/>);
  fireEvent.click(screen.getByRole('button', {name: '选择工作空间'}));
  fireEvent.click(await screen.findByRole('menuitem', {name: '空间B'}));
  expect(directory.select).not.toHaveBeenCalled();
});
