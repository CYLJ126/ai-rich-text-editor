import {act, cleanup, fireEvent, render, renderHook, screen, waitFor,} from '@testing-library/react';
import React from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import messages from '@/locales/zh-CN/aiChat';
import {
  AiApiError,
  type BudgetAccountResponse,
  type ConversationResponse,
  type ConversationTurnResponse,
  getBudget,
  getConversation,
  getInvocationResult,
  getInvocationStatus,
  type InvocationResultResponse,
  type InvocationStatusResponse,
  queryTurnsOfConversation,
  turnsForChat,
} from '@/services/arte-ai';
import ChatPanel from './ChatPanel';
import {chatConfigSchema, DEFAULT_CHAT_CONFIG} from './config';
import {hasAvailableBudget, useChatSession} from './useChatSession';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getBudget: vi.fn(),
  getConversation: vi.fn(),
  getInvocationResult: vi.fn(),
  getInvocationStatus: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  turnsForChat: vi.fn(),
}));
const config = chatConfigSchema.parse({
  ...DEFAULT_CHAT_CONFIG,
  tenantId: 'tenant',
  workspaceId: 'workspace',
  capabilityId: 'text',
  capabilityVersion: 'v1',
  bindingId: 'binding',
  bindingVersion: 'v1',
  budgetRef: 'budget',
});
const conversation: ConversationResponse = {
  conversationId: 'conversation',
  title: '测试会话',
  version: 0,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-07T10:00:00Z',
  updatedAt: '2026-10-07T10:00:00Z',
};
const budget: BudgetAccountResponse = {
  budgetRef: 'budget',
  currency: 'CNY',
  limit: '100.00000000',
  held: '0',
  charged: '0',
  available: '100.00000000',
  rateVersion: {type: 'rate', id: 'rate', version: 'v1'},
  version: 0,
};

function reply<T>(data: T, httpStatus = 200) {
  return {
    httpStatus,
    body: {success: true as const, code: '200', desc: 'OK', data},
  };
}

function turn(sequence = 1): ConversationTurnResponse {
  return {
    turnId: `turn-${sequence}`,
    conversationId: 'conversation',
    sequence,
    parentTurnId: null,
    supersedesTurnId: null,
    userMessage: {
      messageId: `user-${sequence}`,
      role: 'USER',
      content: [{text: `你好 ${sequence}`}],
      toolCalls: [],
      toolCallId: null,
    },
    invocationIds: [`invocation-${sequence}`],
    selectedInvocationId: null,
    version: 0,
    createdAt: conversation.createdAt,
    updatedAt: conversation.updatedAt,
  };
}

function status(
  id: string,
  state: InvocationStatusResponse['state'],
  version = 0,
): InvocationStatusResponse {
  return {
    invocationId: id,
    kind: 'GENERATION',
    conversation: {
      conversationId: 'conversation',
      conversationVersion: 0,
      turnId: 'turn-1',
    },
    state,
    version,
    activeAttemptId: state === 'ACCEPTED' ? null : 'attempt',
    resultAvailable: state === 'SUCCEEDED',
    partial: state === 'SUCCEEDED' ? false : null,
    error: null,
    acceptedAt: conversation.createdAt,
    updatedAt: conversation.updatedAt,
  };
}

function result(id: string): InvocationResultResponse {
  return {
    invocationId: id,
    kind: 'GENERATION',
    result: {
      value: {
        resultId: 'result',
        model: {providerId: 'test', modelId: 'model', revision: null},
        outputs: [
          {
            messageId: 'answer',
            role: 'ASSISTANT',
            content: [{text: '回复 <script>alert(1)</script>'}],
            toolCalls: [],
            toolCallId: null,
          },
        ],
        finishReason: 'STOP',
        complete: true,
        structuredOutput: null,
        usage: {
          basis: 'PROVIDER_REPORTED',
          inputTokens: 5,
          outputTokens: 3,
          totalTokens: 8,
        },
        sources: [],
      },
    },
  };
}

function history(
  records: ConversationTurnResponse[],
  current = 1,
  total = records.length,
) {
  return {
    httpStatus: 200,
    body: {
      success: true as const,
      code: '200',
      desc: 'OK',
      records,
      current,
      size: 10,
      total,
    },
  };
}

const t = (key: string) =>
  messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;

function mount() {
  return renderHook(() => useChatSession(config, conversation, vi.fn()));
}

async function ready(hook: {
  result: { current: ReturnType<typeof useChatSession> };
}) {
  await waitFor(() => {
    expect(hook.result.current.historyLoading).toBe(false);
    expect(hook.result.current.budget).toEqual(budget);
  });
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getConversation).mockResolvedValue(reply(conversation));
  vi.mocked(getBudget).mockResolvedValue(reply(budget));
  vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([]));
  vi.mocked(getInvocationStatus).mockImplementation(async ({invocationId}) =>
    reply(status(invocationId, 'SUCCEEDED', 3)),
  );
  vi.mocked(getInvocationResult).mockImplementation(async ({invocationId}) =>
    reply(result(invocationId)),
  );
  vi.mocked(turnsForChat).mockResolvedValue(
    reply(
      {
        invocationId: 'invocation-1',
        conversationId: 'conversation',
        kind: 'INVOCATION' as const,
        acceptedAt: conversation.createdAt,
      },
      202,
    ),
  );
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('消息、历史和预算', () => {
  it('发送固定配置和最新版本，轮询完成后查询结果并使用新版本发送第二轮', async () => {
    const hook = mount();
    await ready(hook);
    let state: InvocationStatusResponse['state'] = 'ACCEPTED';
    vi.mocked(getInvocationStatus).mockImplementation(
      async ({invocationId}) =>
        reply(status(invocationId, state, state === 'SUCCEEDED' ? 3 : 1)),
    );
    vi.mocked(turnsForChat).mockImplementation(async () => {
      vi.mocked(getConversation).mockResolvedValue(
        reply({...conversation, version: 1}),
      );
      vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
      return reply(
        {
          invocationId: 'invocation-1',
          conversationId: 'conversation',
          kind: 'INVOCATION',
          acceptedAt: conversation.createdAt,
        },
        202,
      );
    });
    act(() => hook.result.current.setDraft('  第一轮  '));
    await act(async () => {
      await hook.result.current.send();
    });
    await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
    const [request, key] = vi.mocked(turnsForChat).mock.calls[0];
    expect(request).toMatchObject({
      scope: {tenantId: 'tenant', workspaceId: 'workspace'},
      conversationId: 'conversation',
      expectedVersion: 0,
      text: '第一轮',
      budgetRef: 'budget',
      capability: {type: 'capability', id: 'text', version: 'v1'},
      binding: {type: 'binding', id: 'binding', version: 'v1'},
      maxInputTokens: config.maxInputTokens,
      generationOptions: {
        maxOutputTokens: config.maxOutputTokens,
        stopSequences: [],
      },
      timeoutSeconds: config.timeoutSeconds,
    });
    expect(key).toBeTruthy();
    expect(hook.result.current.draft).toBe('');
    expect(hook.result.current.activeIds).toEqual(['invocation-1']);
    act(() => hook.result.current.setDraft('第二轮'));
    await act(async () => {
      await hook.result.current.send();
    });
    expect(turnsForChat).toHaveBeenCalledTimes(1);
    state = 'SUCCEEDED';
    await waitFor(() => expect(hook.result.current.activeIds).toEqual([]), {
      timeout: 3000,
    });
    await ready(hook);
    expect(hook.result.current.invocations['invocation-1'].result).toEqual(
      result('invocation-1'),
    );
    expect(getBudget).toHaveBeenCalledTimes(3);
    await act(async () => {
      await hook.result.current.send();
    });
    expect(vi.mocked(turnsForChat).mock.calls[1][0]).toMatchObject({
      expectedVersion: 1,
      text: '第二轮',
    });
    expect(vi.mocked(turnsForChat).mock.calls[1][1]).not.toBe(key);
  });

  it('并发点击只能发送一次，网络结果不明时复用完全相同的请求和幂等键', async () => {
    vi.mocked(turnsForChat).mockRejectedValue(new TypeError('offline'));
    const hook = renderHook(
      ({settings}) => useChatSession(settings, conversation, vi.fn()),
      {initialProps: {settings: config}},
    );
    await ready(hook);
    act(() => hook.result.current.setDraft('原消息'));
    await act(async () => {
      await Promise.all([
        hook.result.current.send(),
        hook.result.current.send(),
      ]);
    });
    expect(turnsForChat).toHaveBeenCalledTimes(1);
    expect(hook.result.current.pending).not.toBeNull();
    hook.rerender({
      settings: {...config, budgetRef: 'another-budget', temperature: 0.7},
    });
    await act(async () => {
      await hook.result.current.send();
    });
    const calls = vi.mocked(turnsForChat).mock.calls;
    expect(calls[1][0]).toEqual(calls[0][0]);
    expect(calls[1][1]).toBe(calls[0][1]);
    expect(calls[1][0].budgetRef).toBe('budget');
  });

  it('409 冲突刷新版本，保留草稿并要求用户手动重新发送', async () => {
    const hook = mount();
    await ready(hook);
    vi.mocked(turnsForChat).mockRejectedValue(
      new AiApiError(409, {success: false, code: '205030', desc: '版本冲突'}),
    );
    vi.mocked(getConversation).mockResolvedValue(
      reply({...conversation, version: 2}),
    );
    act(() => hook.result.current.setDraft('草稿'));
    await act(async () => {
      await hook.result.current.send();
    });
    await ready(hook);
    expect(hook.result.current.pending).toBeNull();
    expect(hook.result.current.draft).toBe('草稿');
    expect(turnsForChat).toHaveBeenCalledTimes(1);
    await act(async () => {
      await hook.result.current.send();
    });
    expect(vi.mocked(turnsForChat).mock.calls[1][0].expectedVersion).toBe(2);
  });

  it('读取最新历史页，关联已提交回复；翻页后仍观察最新页中未完成的调用', async () => {
    vi.mocked(getConversation).mockResolvedValue(
      reply({...conversation, version: 11}),
    );
    vi.mocked(queryTurnsOfConversation).mockImplementation(async ({page}) =>
      history([turn(page?.current === 2 ? 11 : 1)], page?.current ?? 1, 11),
    );
    vi.mocked(getInvocationStatus).mockImplementation(
      async ({invocationId}) =>
        reply(
          status(
            invocationId,
            invocationId === 'invocation-11' ? 'RUNNING' : 'SUCCEEDED',
            2,
          ),
        ),
    );
    const hook = mount();
    await ready(hook);
    expect(hook.result.current.page).toBe(2);
    expect(hook.result.current.turns[0].sequence).toBe(11);
    expect(hook.result.current.activeIds).toEqual(['invocation-11']);
    await act(async () => {
      await hook.result.current.loadHistory(1);
    });
    expect(hook.result.current.page).toBe(1);
    expect(hook.result.current.invocations['invocation-1'].result).toEqual(
      result('invocation-1'),
    );
    expect(hook.result.current.activeIds).toEqual(['invocation-11']);
  });

  it('轮询错误保留调用身份，恢复查询不会再次提交消息', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    let count = 0;
    vi.mocked(getInvocationStatus).mockImplementation(
      async ({invocationId}) => {
        if (++count === 2) throw new TypeError('offline');
        return reply(
          status(invocationId, count === 1 ? 'RUNNING' : 'SUCCEEDED', count),
        );
      },
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(hook.result.current.pollError).toBeTruthy());
    expect(hook.result.current.activeIds).toEqual(['invocation-1']);
    act(() => hook.result.current.resume());
    await waitFor(() => expect(hook.result.current.activeIds).toEqual([]));
    expect(turnsForChat).not.toHaveBeenCalled();
    expect(hook.result.current.pollError).toBeNull();
  });

  it('状态请求挂起时达到观察期限会暂停，继续查询仍使用原调用', async () => {
    const hook = mount();
    await ready(hook);
    vi.useFakeTimers();
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus)
      .mockResolvedValueOnce(reply(status('invocation-1', 'RUNNING', 1)))
      .mockImplementation(() => new Promise(() => {
      }));
    await act(async () => {
      await hook.result.current.loadHistory();
    });
    const signal = vi
      .mocked(getInvocationStatus)
      .mock.calls.at(-1)?.[1]?.signal;
    await act(async () => {
      await vi.advanceTimersByTimeAsync((config.timeoutSeconds + 30) * 1000);
    });
    expect(hook.result.current.pollPaused).toBe(true);
    expect(hook.result.current.activeIds).toEqual(['invocation-1']);
    expect(signal?.aborted).toBe(true);
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'SUCCEEDED', 3)),
    );
    await act(async () => {
      hook.result.current.resume();
    });
    expect(hook.result.current.pollPaused).toBe(false);
    expect(hook.result.current.activeIds).toEqual([]);
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('UNKNOWN 展示部分结果和错误，不自动重发或重新生成', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply<InvocationStatusResponse>({
        ...status('invocation-1', 'UNKNOWN', 3),
        resultAvailable: true,
        partial: true,
        error: {
          code: 'REMOTE_UNKNOWN',
          phase: 'DISPATCH',
          retryable: false,
          sideEffect: 'POSSIBLE',
          certainty: 'UNKNOWN',
          correlationId: 'trace-1',
        },
      }),
    );
    const partial = result('invocation-1');
    if (partial.kind === 'GENERATION') {
      partial.result.value.complete = false;
      partial.result.value.finishReason = 'OTHER';
    }
    vi.mocked(getInvocationResult).mockResolvedValue(reply(partial));
    render(
      <ChatPanel
        config={config}
        conversation={conversation}
        dirty={false}
        onUpdated={vi.fn()}
        t={t}
      />,
    );
    expect(await screen.findByText(t('partialResult'))).toBeInTheDocument();
    expect(screen.getByText('REMOTE_UNKNOWN')).toBeInTheDocument();
    expect(turnsForChat).not.toHaveBeenCalled();
    expect(
      screen.getByRole('textbox', {name: t('messageInput')}),
    ).toBeDisabled();
    expect(screen.getByText(t('unknownOutcome'))).toBeInTheDocument();
  });

  it('预算查询失败或余额为零时禁止新消息，刷新预算后可发送', async () => {
    vi.mocked(getBudget).mockRejectedValue(
      new AiApiError(403, {
        success: false,
        code: '403',
        desc: '预算权限不足',
      }),
    );
    render(
      <ChatPanel
        config={config}
        conversation={conversation}
        dirty={false}
        onUpdated={vi.fn()}
        t={t}
      />,
    );
    expect(await screen.findByText(/预算权限不足/)).toBeInTheDocument();
    expect(
      screen.getByRole('textbox', {name: t('messageInput')}),
    ).toBeDisabled();
    vi.mocked(getBudget).mockResolvedValue(
      reply({...budget, available: '0.00000000'}),
    );
    fireEvent.click(
      await screen.findByRole('button', {
        name: new RegExp(t('refreshBudget')),
      }),
    );
    expect(
      await screen.findByText(t('insufficientBudget')),
    ).toBeInTheDocument();
    vi.mocked(getBudget).mockResolvedValue(reply(budget));
    fireEvent.click(
      await screen.findByRole('button', {
        name: new RegExp(t('refreshBudget')),
      }),
    );
    await waitFor(() =>
      expect(
        screen.getByRole('textbox', {name: t('messageInput')}),
      ).toBeEnabled(),
    );
    fireEvent.change(screen.getByRole('textbox', {name: t('messageInput')}), {
      target: {value: '你好'},
    });
    expect(screen.getByRole('button', {name: t('send')})).toBeEnabled();
  });

  it('回复按文本渲染，金额保留原精度，未应用配置禁止发送', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    const view = render(
      <ChatPanel
        config={config}
        conversation={conversation}
        dirty={false}
        onUpdated={vi.fn()}
        t={t}
      />,
    );
    expect(
      await screen.findByText('回复 <script>alert(1)</script>'),
    ).toBeInTheDocument();
    expect(view.container.querySelector('script')).toBeNull();
    expect(screen.getByText(/100.00000000 CNY/)).toBeInTheDocument();
    view.rerender(
      <ChatPanel
        config={config}
        conversation={conversation}
        dirty
        onUpdated={vi.fn()}
        t={t}
      />,
    );
    expect(
      screen.getByRole('textbox', {name: t('messageInput')}),
    ).toBeDisabled();
  });

  it('离开会话时取消请求，迟到响应不能更新已选会话', async () => {
    let resolve:
      | ((value: ReturnType<typeof reply<ConversationResponse>>) => void)
      | undefined;
    vi.mocked(getConversation).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const updated = vi.fn();
    const hook = renderHook(() =>
      useChatSession(config, conversation, updated),
    );
    const signal = vi.mocked(getConversation).mock.calls[0][1]?.signal;
    hook.unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => {
      resolve?.(reply(conversation));
    });
    expect(updated).not.toHaveBeenCalled();
    expect(queryTurnsOfConversation).not.toHaveBeenCalled();
  });

  it.each(['0', '0.0000', '-0.01', '-1', 'NaN'])('余额 %s 不足', (amount) =>
    expect(hasAvailableBudget(amount)).toBe(false),
  );
  it.each(['0.00000000000000000001', '9007199254740993.01', '1'])(
    '余额 %s 保留十进制精度并允许服务端校验额度',
    (amount) => expect(hasAvailableBudget(amount)).toBe(true),
  );
});
