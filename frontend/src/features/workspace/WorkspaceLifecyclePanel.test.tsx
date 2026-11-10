import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {executeLifecycle, getLifecycleCapabilities, previewClosure} from '@/services/platform/workspaceLifecycle';
import {LifecycleSession, useLifecycleSession} from './LifecycleSession';
import WorkspaceLifecyclePanel from './WorkspaceLifecyclePanel';

vi.mock('@/services/platform/workspaceLifecycle', async original => ({
  ...await original<typeof import('@/services/platform/workspaceLifecycle')>(),
  executeLifecycle: vi.fn(),
  getLifecycleCapabilities: vi.fn(),
  previewClosure: vi.fn()
}));
const scope = {tenantId: 'team', workspaceId: 'shared'};
const view = {scope, name: '共读空间', state: 0 as const, currentRole: 0 as const, version: 2};
const result = {
  operationId: 'operation',
  status: 1 as const,
  delivery: 0 as const,
  resultVersion: 3,
  value: {...view, state: 1},
  acceptedAt: '',
  completedAt: ''
};
const onUpdated = vi.fn(), onClosed = vi.fn();

function Status() {
  return useLifecycleSession()?.status;
}

const tree = () => <LifecycleSession userId="alice"><Status/><WorkspaceLifecyclePanel view={view} onUpdated={onUpdated}
                                                                                      onClosed={onClosed}/></LifecycleSession>;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(getLifecycleCapabilities).mockResolvedValue({
    archive: true,
    restore: false,
    close: true,
    closureInstalled: true,
    defaultWorkspace: false
  });
});
afterEach(cleanup);
it('requires explicit archive confirmation and preserves the fixed target and version', async () => {
  vi.mocked(executeLifecycle).mockResolvedValue(result);
  render(tree());
  fireEvent.change(await screen.findByRole('textbox', {name: '生命周期操作说明'}), {target: {value: '项目已结束'}});
  fireEvent.click(screen.getByRole('button', {name: '归档空间'}));
  expect(executeLifecycle).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '确认归档'}));
  await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1));
  expect(executeLifecycle).toHaveBeenCalledWith({
    action: 'archive',
    scope,
    expectedWorkspaceVersion: 2,
    reason: '项目已结束',
    name: '共读空间'
  }, expect.any(String));
  expect(onClosed).not.toHaveBeenCalled();
});
it('keeps the original command and key after a lost response and browser reload', async () => {
  vi.mocked(executeLifecycle).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(result);
  const first = render(tree());
  fireEvent.click(await screen.findByRole('button', {name: '归档空间'}));
  fireEvent.click(await screen.findByRole('button', {name: '确认归档'}));
  await screen.findByRole('button', {name: '确认原生命周期操作'});
  await waitFor(() => expect(screen.getByText('结果待确认，请用原操作键确认，不要重新创建相同操作。')).toBeInTheDocument());
  const original = vi.mocked(executeLifecycle).mock.calls[0];
  first.unmount();
  render(tree());
  fireEvent.click(screen.getByRole('button', {name: '确认原生命周期操作'}));
  await waitFor(() => expect(executeLifecycle).toHaveBeenCalledTimes(2));
  expect(vi.mocked(executeLifecycle).mock.calls[1]).toEqual(original);
});
it('shows default-space protection without lifecycle actions', async () => {
  vi.mocked(getLifecycleCapabilities).mockResolvedValue({
    archive: false,
    restore: false,
    close: false,
    closureInstalled: true,
    defaultWorkspace: true
  });
  render(tree());
  await screen.findByText('系统默认个人空间受保护，不能归档或关闭。');
  expect(screen.queryByRole('button', {name: '归档空间'})).toBeNull();
});
it('shows non-retryable dependencies before close and prevents submitting', async () => {
  vi.mocked(previewClosure).mockResolvedValue({
    scope, workspaceVersion: 2, retainedResources: {'resources:model': 1},
    blockers: [{domain: 'resources', workKey: 'retention', code: 'TENANT_MODEL_DEPENDENCY', retryable: false}]
  });
  render(tree());
  fireEvent.click(await screen.findByRole('button', {name: '关闭预检'}));
  expect(await screen.findByText(/此空间仍管理未停用的租户模型/)).toBeInTheDocument();
  expect(screen.getByRole('button', {name: '申请关闭空间'})).toBeDisabled();
  expect(executeLifecycle).not.toHaveBeenCalled();
});
