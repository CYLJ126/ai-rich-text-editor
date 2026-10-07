import {act, cleanup, fireEvent, render, screen, waitFor, within,} from '@testing-library/react';
import React from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import messages from '@/locales/zh-CN/aiChat';
import {
  AiApiError,
  type ChatModelOption,
  discoverChatOptions,
  getBudget,
  getConversation,
  listConversations,
  queryTurnsOfConversation,
  turnsForChat,
} from '@/services/arte-ai';
import {CHAT_CONFIG_STORAGE_KEY, chatConfigSchema, DEFAULT_CHAT_CONFIG, saveChatConfig,} from './config';
import AiChatPage from './index';

vi.mock('@umijs/max', () => ({
  request: vi.fn(),
  useIntl: () => ({
    formatMessage: ({id}: { id: string }) =>
      messages[id as keyof typeof messages] ?? id,
  }),
}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  discoverChatOptions: vi.fn(),
  listConversations: vi.fn(),
  getConversation: vi.fn(),
  getBudget: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  turnsForChat: vi.fn(),
}));
const scope = {tenantId: 'tenant-1', workspaceId: 'workspace-1'};
const model: ChatModelOption = {
  displayName: 'DeepSeek',
  binding: {type: 'binding', id: 'default-binding', version: 'v1'},
  capability: {type: 'capability', id: 'text-generation', version: 'v1'},
  contextWindowTokens: 65536,
  limits: {
    maxInputTokens: 65535,
    maxOutputTokens: 4096,
    maxInputBytes: 65536,
    maxOutputBytes: 1048576,
    maxTimeoutSeconds: 120,
  },
  defaults: {maxInputTokens: 32768, maxOutputTokens: 512, timeoutSeconds: 60},
  budgetRefs: ['budget-1'],
};
const alternate: ChatModelOption = {
  ...model,
  displayName: 'Small model',
  binding: {type: 'binding', id: 'small', version: 'v2'},
  capability: {type: 'capability', id: 'small-text', version: 'v2'},
  contextWindowTokens: 1024,
  limits: {
    ...model.limits,
    maxInputTokens: 1023,
    maxOutputTokens: 256,
    maxInputBytes: 2048,
    maxTimeoutSeconds: 30,
  },
  defaults: {maxInputTokens: 768, maxOutputTokens: 256, timeoutSeconds: 30},
  budgetRefs: ['small-budget'],
};
const valid = {
  ...DEFAULT_CHAT_CONFIG,
  ...scope,
  capabilityId: model.capability.id,
  capabilityVersion: 'v1',
  bindingId: model.binding.id,
  bindingVersion: 'v1',
  budgetRef: 'budget-1',
};
const account = {
  budgetRef: 'budget-1',
  currency: 'CNY',
  limit: '100',
  held: '0',
  charged: '0',
  available: '99.876543210987654322',
  rateVersion: {type: 'rate' as const, id: 'rate', version: 'v1'},
  version: 0,
};
const conversation = {
  conversationId: 'conversation-1',
  title: '测试会话',
  version: 4,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE' as const,
  createdAt: '2026-10-07T01:00:00Z',
  updatedAt: '2026-10-07T01:00:00Z',
};

function reply<T>(data: T) {
  return {
    httpStatus: 200,
    body: {success: true as const, code: '200', desc: 'OK', data},
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return {promise, resolve};
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
  localStorage.clear();
  vi.mocked(discoverChatOptions).mockResolvedValue(reply({options: [model]}));
  vi.mocked(getBudget).mockImplementation(async (data) =>
    reply({...account, budgetRef: data.budgetRef}),
  );
  vi.mocked(listConversations).mockResolvedValue({
    httpStatus: 200,
    body: {
      success: true,
      code: '200',
      desc: 'OK',
      records: [],
      current: 1,
      size: 20,
      total: 0,
    },
  });
  vi.mocked(queryTurnsOfConversation).mockResolvedValue({
    httpStatus: 200,
    body: {
      success: true,
      code: '200',
      desc: 'OK',
      records: [],
      current: 1,
      size: 10,
      total: 0,
    },
  });
  vi.mocked(getConversation).mockResolvedValue(reply(conversation));
});
afterEach(cleanup);
function apply() {
  fireEvent.submit(screen.getByRole('form', {name: '测试配置'}));
}

function change(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label, {exact: true}), {
    target: {value},
  });
}

async function load() {
  change('租户 ID', ' tenant-1 ');
  change('工作空间 ID', 'workspace-1');
  fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
  await screen.findByText('DeepSeek · default-binding@v1');
}

async function select(label: string, option: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', {name: label}));
  await waitFor(() =>
    expect(
      document.querySelector(
        '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
      ),
    ).not.toBeNull(),
  );
  const popup = document.querySelector<HTMLElement>(
    '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
  );
  if (!popup) throw new Error('Select did not open');
  fireEvent.click(
    within(popup as HTMLElement).getByText(option, {
      selector: '.ant-select-item-option-content',
    }),
  );
}

function advanced() {
  fireEvent.click(screen.getByText('高级参数'));
}

function saved() {
  return JSON.parse(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY) ?? '{}')
    .config;
}

async function applied() {
  await screen.findByText('本地配置已应用');
  await waitFor(() =>
    expect(screen.getByRole('button', {name: '新建会话'})).toBeEnabled(),
  );
}

async function selectConversation(waitForInput = true) {
  vi.mocked(listConversations).mockResolvedValue({
    httpStatus: 200,
    body: {
      success: true,
      code: '200',
      desc: 'OK',
      records: [conversation],
      current: 1,
      size: 20,
      total: 1,
    },
  });
  fireEvent.click(await screen.findByRole('button', {name: /刷新列表/}));
  fireEvent.click(
    await screen.findByRole('button', {name: '测试会话 conversation-1'}),
  );
  await screen.findByLabelText('会话详情');
  if (waitForInput)
    await waitFor(() =>
      expect(screen.getByRole('textbox', {name: '消息内容'})).toBeEnabled(),
    );
}

describe('配置发现与选择', () => {
  it('未确定空间时不发请求，不能应用或发送', async () => {
    render(<AiChatPage/>);
    expect(discoverChatOptions).not.toHaveBeenCalled();
    expect(listConversations).not.toHaveBeenCalled();
    expect(screen.getByRole('button', {name: '应用配置'})).toBeDisabled();
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await waitFor(() =>
      expect(screen.getAllByText('请填写此项，不能只包含空白')).toHaveLength(2),
    );
  });

  it('单模型和单预算自动选择，查询精确余额，应用后保存固定版本', async () => {
    render(<AiChatPage/>);
    await load();
    expect(discoverChatOptions).toHaveBeenCalledWith(
      {scope},
      expect.objectContaining({signal: expect.any(AbortSignal)}),
    );
    expect(
      await screen.findByText('预算余额：99.876543210987654322 CNY'),
    ).toBeInTheDocument();
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBeNull();
    expect(screen.queryByLabelText('Binding ID')).not.toBeInTheDocument();
    apply();
    await applied();
    expect(saved()).toEqual(valid);
    expect(listConversations).toHaveBeenCalledWith(
      {scope, page: {current: 1, size: 20}},
      expect.anything(),
    );
  });

  it('多个模型由用户选择，切换后更新兼容预算和推荐参数', async () => {
    vi.mocked(discoverChatOptions).mockResolvedValue(
      reply({options: [model, alternate]}),
    );
    render(<AiChatPage/>);
    change('租户 ID', scope.tenantId);
    change('工作空间 ID', scope.workspaceId);
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await waitFor(() =>
      expect(screen.getByRole('combobox', {name: '模型'})).not.toBeDisabled(),
    );
    expect(screen.getByRole('button', {name: '应用配置'})).toBeDisabled();
    await select('模型', 'Small model · small@v2');
    await screen.findByText('small-budget');
    advanced();
    expect(screen.getByLabelText('最大输入 Token')).toHaveValue('768');
    expect(screen.getByLabelText('最大输出 Token')).toHaveValue('256');
    expect(screen.getByLabelText('超时（秒）')).toHaveValue('30');
    expect(screen.getByLabelText('最大输出 Token')).toHaveAttribute(
      'aria-valuemax',
      '256',
    );
    apply();
    await applied();
    expect(saved()).toMatchObject({
      bindingId: 'small',
      bindingVersion: 'v2',
      capabilityId: 'small-text',
      capabilityVersion: 'v2',
      budgetRef: 'small-budget',
      maxInputTokens: 768,
    });
  });

  it('多个兼容预算不任意选择，选择后才能应用', async () => {
    vi.mocked(discoverChatOptions).mockResolvedValue(
      reply({options: [{...model, budgetRefs: ['budget-1', 'budget-2']}]}),
    );
    render(<AiChatPage/>);
    await load();
    apply();
    await screen.findByText('请填写此项，不能只包含空白');
    expect(getBudget).not.toHaveBeenCalled();
    await select('预算账户引用', 'budget-2');
    await waitFor(() =>
      expect(getBudget).toHaveBeenCalledWith(
        {scope, budgetRef: 'budget-2'},
        expect.anything(),
      ),
    );
    apply();
    await applied();
    expect(saved().budgetRef).toBe('budget-2');
  });

  it('校验合并窗口与超时，推荐额度按所选模型恢复且应用前不保存', async () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    render(<AiChatPage/>);
    await applied();
    advanced();
    const before = localStorage.getItem(CHAT_CONFIG_STORAGE_KEY);
    change('最大输入 Token', '65535');
    change('最大输出 Token', '4096');
    apply();
    await screen.findByText('输入与输出 Token 额度之和超过模型上下文窗口。');
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBe(before);
    change('超时（秒）', '1.5');
    apply();
    await screen.findByText('请输入整数');
    fireEvent.click(screen.getByRole('button', {name: '使用模型推荐额度'}));
    expect(screen.getByLabelText('最大输入 Token')).toHaveValue('32768');
    apply();
    await waitFor(() =>
      expect(screen.queryByText('配置有未应用修改')).not.toBeInTheDocument(),
    );
  });

  it('恢复旧选择前重新发现，保留合法额度和采样参数', async () => {
    const previous = chatConfigSchema.parse({
      ...valid,
      maxInputTokens: 4096,
      maxOutputTokens: 1024,
      temperature: 0.25,
      topP: 0.8,
    });
    saveChatConfig(previous);
    const pending = deferred<Awaited<ReturnType<typeof discoverChatOptions>>>();
    vi.mocked(discoverChatOptions).mockReturnValueOnce(pending.promise);
    render(<AiChatPage/>);
    expect(listConversations).not.toHaveBeenCalled();
    await act(async () => pending.resolve(reply({options: [model]})));
    await applied();
    advanced();
    expect(screen.getByLabelText('最大输入 Token')).toHaveValue('4096');
    expect(screen.getByLabelText('Temperature（0～2）')).toHaveValue('0.25');
    expect(saved()).toEqual(previous);
  });

  it('旧引用失效或参数超限不自动恢复为可提交配置', async () => {
    saveChatConfig(chatConfigSchema.parse({...valid, bindingVersion: 'v0'}));
    render(<AiChatPage/>);
    await screen.findByText(
      '保存的模型、预算或参数已不再可用，请重新选择并应用配置。',
    );
    expect(listConversations).not.toHaveBeenCalled();
    expect(screen.getByRole('button', {name: '新建会话'})).toBeDisabled();
    expect(saved().bindingVersion).toBe('v0');
    apply();
    await applied();
    expect(saved().bindingVersion).toBe('v1');
  });

  it('空结果与失败可重试，401 不自动重复请求', async () => {
    vi.mocked(discoverChatOptions).mockRejectedValueOnce(
      new AiApiError(401, {desc: '需要登录'}),
    );
    render(<AiChatPage/>);
    change('租户 ID', scope.tenantId);
    change('工作空间 ID', scope.workspaceId);
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await screen.findByText('登录已失效，请重新登录。');
    expect(discoverChatOptions).toHaveBeenCalledTimes(1);
    vi.mocked(discoverChatOptions).mockResolvedValueOnce(
      reply({options: []}),
    );
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await screen.findByText(
      '当前空间没有可用的模型与预算组合，请联系管理员配置。',
    );
    expect(screen.getByRole('button', {name: '应用配置'})).toBeDisabled();
  });

  it('修改空间取消旧发现，迟到结果不能恢复旧模型', async () => {
    const pending = deferred<Awaited<ReturnType<typeof discoverChatOptions>>>();
    vi.mocked(discoverChatOptions).mockReturnValueOnce(pending.promise);
    render(<AiChatPage/>);
    change('租户 ID', scope.tenantId);
    change('工作空间 ID', scope.workspaceId);
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    const signal = vi.mocked(discoverChatOptions).mock.calls[0][1]?.signal;
    change('工作空间 ID', 'workspace-2');
    expect(signal?.aborted).toBe(true);
    await act(async () => pending.resolve(reply({options: [model]})));
    expect(
      screen.queryByText('DeepSeek · default-binding@v1'),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', {name: '应用配置'})).toBeDisabled();
  });

  it('仅调整参数保留会话，应用新空间后重置会话', async () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    render(<AiChatPage/>);
    await applied();
    await selectConversation();
    advanced();
    change('最大输出 Token', '1024');
    apply();
    await waitFor(() =>
      expect(screen.getByRole('textbox', {name: '消息内容'})).toBeEnabled(),
    );
    expect(screen.getByLabelText('会话详情')).toBeInTheDocument();
    change('工作空间 ID', 'workspace-2');
    expect(screen.getByRole('button', {name: /发送消息/})).toBeDisabled();
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await screen.findByText('DeepSeek · default-binding@v1');
    apply();
    await waitFor(() => expect(saved().workspaceId).toBe('workspace-2'));
    expect(screen.queryByLabelText('会话详情')).not.toBeInTheDocument();
  });

  it.each([new TypeError('network'), new AiApiError(408, {desc: 'timeout'}), new AiApiError(503, {
    code: '205001',
    desc: 'unavailable'
  })])('发现的引用进入聊天请求，未确认错误 %s 的重试保持原模型与幂等键', async (error) => {
    saveChatConfig(chatConfigSchema.parse(valid));
    vi.mocked(discoverChatOptions).mockResolvedValue(
      reply({options: [model, alternate]}),
    );
    vi.mocked(turnsForChat).mockRejectedValue(error);
    render(<AiChatPage/>);
    await applied();
    await selectConversation();
    change('消息内容', '你好');
    fireEvent.click(screen.getByRole('button', {name: /发送消息/}));
    await screen.findByRole('button', {name: /重试提交/});
    expect(screen.queryByText(messages['app.aiChat.configurationRejected'])).not.toBeInTheDocument();
    expect(saved()).toEqual(valid);
    const original = vi.mocked(turnsForChat).mock.calls[0];
    expect(original[0]).toMatchObject({
      binding: model.binding,
      capability: model.capability,
      budgetRef: 'budget-1',
    });
    await select('模型', 'Small model · small@v2');
    await waitFor(() =>
      expect(screen.getByLabelText('最大输入 Token')).toHaveValue('768'),
    );
    apply();
    await waitFor(() => expect(saved().bindingId).toBe('small'));
    await waitFor(() =>
      expect(screen.queryByText('配置有未应用修改')).not.toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', {name: /重试提交/})).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole('button', {name: /重试提交/}));
    await waitFor(() => expect(turnsForChat).toHaveBeenCalledTimes(2));
    expect(vi.mocked(turnsForChat).mock.calls[1].slice(0, 2)).toEqual(
      original.slice(0, 2),
    );
  });

  it.each([
    [400, '205001'], [400, '205002'], [404, '205023'],
    [409, '205025'], [400, '205026'], [401, 'unauthenticated'], [403, 'forbidden'],
  ])('提交明确拒绝 HTTP %s / %s 时清除缓存并要求重新发现，保留消息草稿', async (status, code) => {
    saveChatConfig(chatConfigSchema.parse(valid));
    vi.mocked(turnsForChat).mockRejectedValueOnce(new AiApiError(status, {code, desc: '配置被撤回'}));
    render(<AiChatPage/>);
    await applied();
    await selectConversation();
    change('消息内容', '保留这条消息');
    const budgetQueries = vi.mocked(getBudget).mock.calls.length;
    fireEvent.click(screen.getByRole('button', {name: /发送消息/}));
    await screen.findByText(messages['app.aiChat.configurationRejected']);
    expect(screen.getByRole('button', {name: /发送消息/})).toBeDisabled();
    expect(screen.getByRole('button', {name: '应用配置'})).toBeDisabled();
    expect(screen.getByRole('textbox', {name: '消息内容'})).toHaveValue('保留这条消息');
    expect(screen.queryByRole('button', {name: /重试提交/})).not.toBeInTheDocument();
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBeNull();
    expect(discoverChatOptions).toHaveBeenCalledTimes(1);
    expect(getBudget).toHaveBeenCalledTimes(budgetQueries);
    // Empty discovery cannot reactivate the revoked selection, even through programmatic submit.
    vi.mocked(discoverChatOptions).mockResolvedValueOnce(reply({options: []}));
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await screen.findByText('当前空间没有可用的模型与预算组合，请联系管理员配置。');
    apply();
    expect(screen.getByRole('button', {name: /发送消息/})).toBeDisabled();
    // Explicit loading and applying after access is restored retains the conversation and draft.
    fireEvent.click(screen.getByRole('button', {name: /加载可用配置/}));
    await screen.findByText('DeepSeek · default-binding@v1');
    apply();
    await waitFor(() => expect(screen.getByRole('button', {name: /发送消息/})).toBeEnabled());
    expect(screen.queryByText(messages['app.aiChat.configurationRejected'])).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', {name: '消息内容'})).toHaveValue('保留这条消息');
    expect(turnsForChat).toHaveBeenCalledTimes(1);
    expect(saved()).toEqual(valid);
  });

  it('旧模型的未确认提交重试被拒绝，不使已应用的新模型失效', async () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    vi.mocked(discoverChatOptions).mockResolvedValue(reply({options: [model, alternate]}));
    vi.mocked(turnsForChat).mockRejectedValueOnce(new TypeError('network'))
      .mockRejectedValueOnce(new AiApiError(400, {code: '205002', desc: '旧模型已停用'}));
    render(<AiChatPage/>);
    await applied();
    await selectConversation();
    change('消息内容', '你好');
    fireEvent.click(screen.getByRole('button', {name: /发送消息/}));
    await screen.findByRole('button', {name: /重试提交/});
    const original = vi.mocked(turnsForChat).mock.calls[0];
    await select('模型', 'Small model · small@v2');
    apply();
    await waitFor(() => expect(saved().bindingId).toBe('small'));
    fireEvent.click(screen.getByRole('button', {name: /重试提交/}));
    await screen.findByText(/旧模型已停用/);
    expect(vi.mocked(turnsForChat).mock.calls[1].slice(0, 2)).toEqual(original.slice(0, 2));
    expect(screen.queryByText(messages['app.aiChat.configurationRejected'])).not.toBeInTheDocument();
    expect(saved().bindingId).toBe('small');
    await waitFor(() => expect(screen.getByRole('button', {name: /发送消息/})).toBeEnabled());
  });

  it('缓存引用仍存在但额度超过新窗口时，必须修正并手动应用', async () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    vi.mocked(discoverChatOptions).mockResolvedValue(reply({options: [{...model, contextWindowTokens: 1024}]}));
    render(<AiChatPage/>);
    await screen.findByText('保存的模型、预算或参数已不再可用，请重新选择并应用配置。');
    expect(listConversations).not.toHaveBeenCalled();
    apply();
    await screen.findByText('输入与输出 Token 额度之和超过模型上下文窗口。');
    expect(listConversations).not.toHaveBeenCalled();
    change('最大输入 Token', '512');
    apply();
    await applied();
    expect(saved().maxInputTokens).toBe(512);
  });

  it('预算未初始化仍保留选择，余额查询失败不会启用发送', async () => {
    vi.mocked(getBudget).mockRejectedValue(
      new AiApiError(400, {desc: '预算未初始化'}),
    );
    render(<AiChatPage/>);
    await load();
    await screen.findByText('预算未初始化');
    apply();
    await applied();
    await selectConversation(false);
    expect(screen.getByRole('button', {name: /发送消息/})).toBeDisabled();
  });

  it('清除配置取消查询，并保留其他浏览器存储', async () => {
    saveChatConfig(chatConfigSchema.parse(valid));
    localStorage.setItem('user_token', 'keep');
    render(<AiChatPage/>);
    await applied();
    fireEvent.click(screen.getByRole('button', {name: '清除配置'}));
    expect(screen.getByLabelText('租户 ID')).toHaveValue('');
    expect(localStorage.getItem(CHAT_CONFIG_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem('user_token')).toBe('keep');
    expect(screen.getByRole('button', {name: '新建会话'})).toBeDisabled();
  });

  it('缓存损坏和保存失败提供提示，保存失败不阻止本页应用', async () => {
    localStorage.setItem(CHAT_CONFIG_STORAGE_KEY, 'bad json');
    render(<AiChatPage/>);
    expect(
      screen.getByText('保存的配置无效或版本不兼容，请重新填写。'),
    ).toBeInTheDocument();
    await load();
    vi.spyOn(localStorage, 'setItem').mockImplementationOnce(() => {
      throw new Error('blocked');
    });
    apply();
    await screen.findByText(
      '配置已在当前页面应用，但未能保存；刷新后可能无法恢复。',
    );
    expect(screen.getByText('本地配置已应用')).toBeInTheDocument();
  });
});
