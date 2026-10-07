import {cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {request} from '@umijs/max';
import React from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import messages from '@/locales/zh-CN/aiChat';
import {CHAT_CONFIG_STORAGE_KEY, chatConfigSchema, DEFAULT_CHAT_CONFIG, saveChatConfig,} from './config';
import AiChatPage from './index';

vi.mock('@umijs/max', () => ({
  request: vi.fn(),
  useIntl: () => ({
    formatMessage: ({
                      id,
                      defaultMessage,
                    }: {
      id: string;
      defaultMessage?: string;
    }) => messages[id as keyof typeof messages] ?? defaultMessage ?? id,
  }),
}));

const valid = {
  ...DEFAULT_CHAT_CONFIG,
  tenantId: 'tenant-1',
  workspaceId: 'workspace-1',
  capabilityId: 'text-generation',
  capabilityVersion: 'v1',
  bindingId: 'default-binding',
  bindingVersion: 'v1',
  budgetRef: 'budget-1',
};

beforeEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
  localStorage.clear();
});
afterEach(cleanup);

function apply() {
  fireEvent.submit(screen.getByRole('form', {name: '测试配置'}));
}

function fillReferences() {
  const entries = [
    ['租户 ID', ' tenant-1 '],
    ['工作空间 ID', 'workspace-1'],
    ['Capability ID', 'text-generation'],
    ['Capability 版本', 'v1'],
    ['Binding ID', 'default-binding'],
    ['Binding 版本', 'v1'],
    ['预算账户引用', 'budget-1'],
  ];
  for (const [label, value] of entries)
    fireEvent.change(screen.getByLabelText(label, {exact: true}), {
      target: {value},
    });
}

describe('第二步页面', () => {
  it('展示三个区域和真实空状态，暂不发送接口请求', () => {
    render(<AiChatPage/>);
    expect(
      screen.getByRole('main', {name: 'AI 对话测试'}),
    ).toBeInTheDocument();
    expect(screen.getByText('会话列表')).toBeInTheDocument();
    expect(screen.getByText('消息与历史')).toBeInTheDocument();
    expect(screen.getByRole('button', {name: '新建会话'})).toBeDisabled();
    expect(screen.getByRole('button', {name: '发送消息'})).toBeDisabled();
    expect(screen.getByRole('textbox', {name: '消息内容'})).toBeDisabled();
    expect(screen.getByText(/预算余额.*未查询/)).toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();
  });

  it('未填写引用不能应用配置，并在字段显示校验错误', async () => {
    render(<AiChatPage/>);
    apply();
    await waitFor(() =>
      expect(screen.getAllByText('请填写此项，不能只包含空白')).toHaveLength(7),
    );
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBeNull();
    expect(screen.queryByText('本地配置已应用')).not.toBeInTheDocument();
  });

  it('填写并应用后保存配置，草稿不覆盖已应用值，重新挂载可以恢复', async () => {
    const view = render(<AiChatPage/>);
    fillReferences();
    apply();
    await screen.findByText('本地配置已应用');
    const saved = localStorage.getItem(CHAT_CONFIG_STORAGE_KEY);
    expect(JSON.parse(saved ?? '{}').config).toEqual(valid);
    expect(
      screen.getByText('配置已就绪，下一步接入会话管理'),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('租户 ID', {exact: true}), {
      target: {value: 'draft-tenant'},
    });
    expect(screen.getByText('配置有未应用修改')).toBeInTheDocument();
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBe(saved);
    view.unmount();
    render(<AiChatPage/>);
    expect(screen.getByLabelText('租户 ID', {exact: true})).toHaveValue(
      'tenant-1',
    );
    expect(screen.getByText('本地配置已应用')).toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();
  });

  it('拒绝 latest 和小数超时，旧配置保持可用', async () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    render(<AiChatPage/>);
    const saved = localStorage.getItem(CHAT_CONFIG_STORAGE_KEY);
    fireEvent.change(
      screen.getByLabelText('Capability 版本', {exact: true}),
      {target: {value: 'latest'}},
    );
    fireEvent.change(screen.getByLabelText('超时（秒）', {exact: true}), {
      target: {value: '1.5'},
    });
    apply();
    await screen.findByText('请填写固定发布版本，不能使用 latest');
    expect(screen.getByText('请输入整数')).toBeInTheDocument();
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBe(saved);
  });

  it('清除已恢复配置，回到空引用与初始数值', () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    render(<AiChatPage/>);
    fireEvent.click(screen.getByRole('button', {name: '清除配置'}));
    expect(screen.getByLabelText('租户 ID', {exact: true})).toHaveValue('');
    expect(
      screen.getByLabelText('最大输入 Token', {exact: true}),
    ).toHaveValue('4096');
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBeNull();
    expect(screen.queryByText('本地配置已应用')).not.toBeInTheDocument();
    expect(screen.getByText('待配置')).toBeInTheDocument();
  });

  it('缓存损坏时显示提示并允许重新配置', () => {
    localStorage.setItem(CHAT_CONFIG_STORAGE_KEY, 'bad json');
    render(<AiChatPage/>);
    expect(
      screen.getByText('保存的配置无效或版本不兼容，请重新填写。'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', {name: '应用配置'})).toBeEnabled();
  });

  it('保存失败时仍应用当前配置，明确提示刷新风险', async () => {
    render(<AiChatPage/>);
    fillReferences();
    vi.spyOn(localStorage, 'setItem').mockImplementationOnce(() => {
      throw new Error('blocked');
    });
    apply();
    await screen.findByText(
      '配置已在当前页面应用，但未能保存；刷新后可能无法恢复。',
    );
    expect(screen.getByText('本地配置已应用')).toBeInTheDocument();
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBeNull();
  });
});
