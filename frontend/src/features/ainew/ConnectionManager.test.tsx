import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/connection';
import ConnectionManager from './ConnectionManager';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/connection', async original => ({
  ...(await original<typeof import('@/services/platform/connection')>()),
  getConnections: vi.fn(), executeConnectionCommand: vi.fn(),
}));
const empty: api.ConnectionView = {
  version: 0,
  initialized: false,
  connections: [],
  allowedOrigins: ['https://api.deepseek.com']
};
const saved: api.ConnectionView = {
  version: 1, initialized: true, allowedOrigins: empty.allowedOrigins, connections: [{
    reference: {type: 'connection', id: 'personal-deepseek', version: 'db-v1'}, providerId: 'deepseek',
    protocol: {type: 'protocol', id: 'chat-completions-sse', version: 'v1'}, endpoint: 'https://api.deepseek.com/v1',
    state: 'ENABLED', connectTimeoutSeconds: 5, responseTimeoutSeconds: 120, maxResponseBytes: 1048576,
  }]
};
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(api.getConnections).mockResolvedValue(empty);
  vi.mocked(api.executeConnectionCommand).mockResolvedValue(saved);
});
afterEach(cleanup);

async function create() {
  await screen.findByText('数据库目录为空，可以直接创建第一个审核连接。');
  fireEvent.click(screen.getByRole('button', {name: '创建连接新版本'}));
  fireEvent.change(screen.getByRole('textbox', {name: '连接标识'}), {target: {value: 'personal-deepseek'}});
  fireEvent.change(screen.getByRole('textbox', {name: 'API 基础地址'}), {target: {value: 'https://api.deepseek.com/v1'}});
  fireEvent.click(screen.getByRole('button', {name: '发布连接版本'}));
}

it('creates the first connection with explicit CAS fields and no credentials', async () => {
  render(<ConnectionManager userId="alice"/>);
  await create();
  await screen.findByText('连接操作已确认并保存到数据库；后续读取与启动使用数据库目录。');
  expect(api.executeConnectionCommand).toHaveBeenCalledWith({
    kind: 'publish', request: {
      expectedVersion: 0, settings: {
        id: 'personal-deepseek', providerId: 'deepseek', endpoint: 'https://api.deepseek.com/v1',
        connectTimeoutSeconds: 5, responseTimeoutSeconds: 120, maxResponseBytes: 1048576,
      }
    }
  }, expect.any(String));
  expect(screen.getByText('deepseek · personal-deepseek@db-v1')).toBeInTheDocument();
});

it('restores an uncertain original operation without resubmitting and retries the same key', async () => {
  vi.mocked(api.executeConnectionCommand).mockRejectedValueOnce(new TypeError('network'));
  render(<ConnectionManager userId="alice"/>);
  await create();
  await screen.findByRole('button', {name: '重试确认原连接操作'});
  const original = vi.mocked(api.executeConnectionCommand).mock.calls[0];
  cleanup();
  render(<ConnectionManager userId="alice"/>);
  await screen.findByRole('button', {name: '重试确认原连接操作'});
  expect(api.executeConnectionCommand).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', {name: '重试确认原连接操作'}));
  await screen.findByText('连接操作已确认并保存到数据库；后续读取与启动使用数据库目录。');
  expect(vi.mocked(api.executeConnectionCommand).mock.calls[1]).toEqual(original);
});

it('blocks a new mutation when session recovery storage fails', async () => {
  render(<ConnectionManager userId="alice"/>);
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('unavailable');
  });
  try {
    await create();
    await screen.findByText('恢复记录无法可靠保存或读取，已阻止新操作；请保留本页并检查浏览器存储。');
    expect(api.executeConnectionCommand).not.toHaveBeenCalled();
  } finally {
    storage.mockRestore();
  }
});

it('shows permission denial without offering a writable form', async () => {
  vi.mocked(api.getConnections).mockRejectedValue(new AiApiError(403, {}));
  render(<ConnectionManager userId="alice"/>);
  await screen.findByText('只有平台配置管理员可以管理审核连接；租户或空间角色不会自动获得此权限。');
  expect(screen.queryByRole('button', {name: '发布连接版本'})).not.toBeInTheDocument();
});

it('reports a version conflict and reloads before creating a fresh operation', async () => {
  vi.mocked(api.executeConnectionCommand).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  render(<ConnectionManager userId="alice"/>);
  await create();
  await screen.findByText('目录版本已变化或原操作参数不一致，请确认原操作或重新读取目录后修改。');
  expect(screen.queryByRole('button', {name: '重试确认原连接操作'})).not.toBeInTheDocument();
  vi.mocked(api.getConnections).mockResolvedValue({...empty, initialized: true, version: 4});
  fireEvent.click(screen.getByRole('button', {name: /重新读取连接目录/}));
  await screen.findByText(/目录版本 4/);
  fireEvent.click(screen.getByRole('button', {name: '发布连接版本'}));
  await waitFor(() => expect(api.executeConnectionCommand).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.executeConnectionCommand).mock.calls[1][0].request.expectedVersion).toBe(4);
  expect(vi.mocked(api.executeConnectionCommand).mock.calls[1][1]).not.toBe(vi.mocked(api.executeConnectionCommand).mock.calls[0][1]);
});

it('does not restore another account’s pending connection mutation', async () => {
  vi.mocked(api.executeConnectionCommand).mockRejectedValueOnce(new TypeError('network'));
  const {rerender} = render(<ConnectionManager userId="alice"/>);
  await create();
  await screen.findByRole('button', {name: '重试确认原连接操作'});
  rerender(<ConnectionManager userId="bob"/>);
  await screen.findByText('尚无审核连接。');
  expect(screen.queryByRole('button', {name: '重试确认原连接操作'})).not.toBeInTheDocument();
});

it('selects a published connection in the right form and protects edits until cancellation', async () => {
  vi.mocked(api.getConnections).mockResolvedValue(saved);
  render(<ConnectionManager userId="alice"/>);
  expect(screen.queryByRole('textbox', {name: '连接标识'})).not.toBeInTheDocument();
  const card = await screen.findByRole('button', {name: '以此版本编辑 personal-deepseek@db-v1'});
  fireEvent.keyDown(card, {key: 'Enter'});
  expect(card).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('textbox', {name: 'API 基础地址'})).toHaveValue('https://api.deepseek.com/v1');
  fireEvent.change(screen.getByRole('textbox', {name: 'API 基础地址'}), {target: {value: 'https://api.deepseek.com/v2'}});
  expect(screen.getByRole('button', {name: '创建连接新版本'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button', {name: '重新读取连接目录'}));
  await waitFor(() => expect(api.getConnections).toHaveBeenCalledTimes(2));
  expect(screen.getByRole('textbox', {name: 'API 基础地址'})).toHaveValue('https://api.deepseek.com/v2');
  fireEvent.click(screen.getByRole('button', {name: '放弃连接修改'}));
  fireEvent.click(screen.getByRole('button', {name: '创建连接新版本'}));
  expect(screen.getByRole('textbox', {name: '连接标识'})).toHaveValue('');
  expect(screen.getByRole('textbox', {name: 'API 基础地址'})).toHaveValue('');
  expect(api.executeConnectionCommand).not.toHaveBeenCalled();
});
