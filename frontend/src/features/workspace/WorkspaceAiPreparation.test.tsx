import React from 'react';
import {cleanup, fireEvent, render, screen} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/ai';
import {AiApiError} from '@/services/arte-ai';
import WorkspaceAiPreparation from './WorkspaceAiPreparation';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/ai', () => ({getWorkspaceAi: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const view: api.WorkspaceAiView = {
  mode: 'DATABASE', configuration: null, workspaceVersion: 2, canManage: true,
  needsImport: false, templateVersion: 1, defaults: null, models: []
};
const response = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.getWorkspaceAi).mockResolvedValue(response(view));
});
afterEach(cleanup);

it('prepares scope configuration before mounting conversation and discovery consumers', async () => {
  let finish!: () => void;
  vi.mocked(api.getWorkspaceAi).mockImplementation(() => new Promise((resolve) => {
    finish = () => resolve(response(view));
  }));
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy={false}><p>聊天消费者</p>
  </WorkspaceAiPreparation>);
  expect(screen.queryByText('聊天消费者')).not.toBeInTheDocument();
  expect(screen.getByText('正在读取空间 AI 配置…')).toBeInTheDocument();
  finish();
  await screen.findByText('聊天消费者');
  expect(api.getWorkspaceAi).toHaveBeenCalledWith(scope, expect.objectContaining({signal: expect.any(AbortSignal)}));
});

it('skips public provisioning for an existing manual legacy AI scope', () => {
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy><p>旧范围</p></WorkspaceAiPreparation>);
  expect(screen.getByText('旧范围')).toBeInTheDocument();
  expect(api.getWorkspaceAi).not.toHaveBeenCalled();
});

it('keeps consumers unmounted after rejected provisioning and supports explicit recheck', async () => {
  vi.mocked(api.getWorkspaceAi).mockRejectedValueOnce(new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'}));
  render(<WorkspaceAiPreparation scope={scope} context={context} legacy={false}><p>聊天消费者</p>
  </WorkspaceAiPreparation>);
  await screen.findByText('此空间暂不可用，请联系空间负责人或平台管理员确认访问权限与 AI 配置。');
  expect(screen.queryByText('聊天消费者')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '重新检查'}));
  await screen.findByText('聊天消费者');
});
