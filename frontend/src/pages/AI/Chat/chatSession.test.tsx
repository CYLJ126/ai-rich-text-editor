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
  watchInvocation,
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
  watchInvocation: vi.fn(),
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

it('按 UTF-8 字节阻止新的超限消息，参数变化不改写尚未确认的重试', async () => {
  vi.mocked(turnsForChat).mockRejectedValue(new TypeError('offline'));
  const hook = renderHook(({limit}) => useChatSession(config, conversation, vi.fn(), limit), {initialProps: {limit: 5}});
  await ready(hook);
  act(() => hook.result.current.setDraft('你好'));
  await act(async () => hook.result.current.send());
  expect(turnsForChat).not.toHaveBeenCalled();
  hook.rerender({limit: 6});
  await act(async () => hook.result.current.send());
  expect(turnsForChat).toHaveBeenCalledTimes(1);
  const original = vi.mocked(turnsForChat).mock.calls[0];
  hook.rerender({limit: 1});
  await act(async () => hook.result.current.send());
  expect(vi.mocked(turnsForChat).mock.calls[1].slice(0, 2)).toEqual(original.slice(0, 2));
});

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
  vi.mocked(watchInvocation).mockImplementation(
    async (_, _event, {signal, onConnected}) => {
      onConnected?.();
      await new Promise<void>((resolve) => {
        if (signal.aborted) resolve();
        else signal.addEventListener('abort', () => resolve(), {once: true});
      });
    },
  );
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
  it('发送固定配置和最新版本，SSE 完成后查询结果并使用新版本发送第二轮', async () => {
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
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls.at(-1)?.[1]({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'TERMINAL',
      });
    });
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

  it('正常 SSE 连接不循环查状态，完成通知只读取结果并刷新历史预算', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    const reads = vi.mocked(getInvocationStatus).mock.calls.length;
    vi.useFakeTimers();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    const notify = vi.mocked(watchInvocation).mock.calls[0][1];
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 3,
        kind: 'OUTPUT',
      });
    });
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'SUCCEEDED', 3)),
    );
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'TERMINAL',
      });
    });
    expect(hook.result.current.activeIds).toEqual([]);
    expect(getInvocationResult).toHaveBeenCalledOnce();
    expect(getBudget).toHaveBeenCalledTimes(2);
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('实时片段短暂乱序时有界暂存，缺口补齐后立即显示，不等待落库或轮询', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    const options = vi.mocked(watchInvocation).mock.calls[0][2];
    const raw = (offset: number, text: string) =>
      options.onText?.({
        executionId: 'invocation-1',
        attemptId: 'attempt',
        offset,
        text,
      });
    const reads = vi.mocked(getInvocationStatus).mock.calls.length;
    await act(async () => {
      raw(2, '世界');
      raw(2, '世界');
      raw(4, '！');
      raw(0, '你好');
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '你好世界！',
    );
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('首次状态查询挂起仍建立 SSE 并显示模型文字', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus)
      .mockResolvedValueOnce(reply(status('invocation-1', 'RUNNING', 1)))
      .mockImplementation(() => new Promise(() => {
      }));
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    await act(async () => {
      vi.mocked(watchInvocation).mock.calls[0][2].onText?.({
        executionId: 'invocation-1',
        attemptId: 'attempt',
        offset: 0,
        text: '首字',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '首字',
    );
  });

  it('模型预览立即显示，Redis 回送与落库重放不重复，缺口由落库补齐，终态校准', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    const [_, notify, options] = vi.mocked(watchInvocation).mock.calls[0];
    const raw = (offset: number, text: string, attemptId = 'attempt') =>
      options.onText?.({
        executionId: 'invocation-1',
        attemptId,
        offset,
        text,
      });
    await act(async () => {
      raw(0, '你🙂');
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '你🙂',
    );
    const reads = vi.mocked(getInvocationStatus).mock.calls.length;
    await act(async () => {
      raw(0, '你🙂');
      raw(3, '\n 好');
      raw(6, '错误尝试', 'another-attempt');
      await notify({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'OUTPUT',
        text: '你🙂\n 好',
      });
      raw(8, '！');
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '你🙂\n 好',
    );
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 5,
        kind: 'OUTPUT',
        text: '世界',
      });
      raw(8, '！');
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '你🙂\n 好世界！',
    );
    await act(async () => {
      hook.result.current.resume();
    });
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledTimes(2));
    expect(vi.mocked(watchInvocation).mock.calls[1][0].afterSequence).toBe(5);
    await act(async () => {
      vi.mocked(watchInvocation).mock.calls[1][2].onText?.({
        executionId: 'invocation-1',
        attemptId: 'attempt',
        offset: 8,
        text: '！',
      });
      await vi.mocked(watchInvocation).mock.calls[1][1]({
        executionId: 'invocation-1',
        sequence: 6,
        kind: 'OUTPUT',
        text: '！',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '你🙂\n 好世界！',
    );
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'SUCCEEDED', 3)),
    );
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls[1][1]({
        executionId: 'invocation-1',
        sequence: 7,
        kind: 'TERMINAL',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].result).toEqual(
      result('invocation-1'),
    );
    expect(turnsForChat).not.toHaveBeenCalled();
    hook.unmount();
    await act(async () => {
      raw(9, '卸载后不显示');
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '你🙂\n 好世界！',
    );
  });

  it('实时文字保持空白、重复序号不重复追加，历史刷新和重连保留文字，终态使用完整结果', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    const notify = vi.mocked(watchInvocation).mock.calls[0][1];
    const reads = vi.mocked(getInvocationStatus).mock.calls.length;
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'OUTPUT',
        text: ' 你\n',
      });
      await notify({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'OUTPUT',
        text: ' 你\n',
      });
      await notify({
        executionId: 'invocation-1',
        sequence: 5,
        kind: 'OUTPUT',
        text: '好 ',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      ' 你\n好 ',
    );
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    expect(getInvocationResult).not.toHaveBeenCalled();
    await act(async () => {
      await hook.result.current.loadHistory();
      hook.result.current.resume();
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      ' 你\n好 ',
    );
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledTimes(2));
    expect(vi.mocked(watchInvocation).mock.calls[1][0].afterSequence).toBe(5);
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls[1][1]({
        executionId: 'invocation-1',
        sequence: 5,
        kind: 'OUTPUT',
        text: '重复',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      ' 你\n好 ',
    );
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'SUCCEEDED', 3)),
    );
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls[1][1]({
        executionId: 'invocation-1',
        sequence: 6,
        kind: 'TERMINAL',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].result).toEqual(
      result('invocation-1'),
    );
    expect(getInvocationResult).toHaveBeenCalledOnce();
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('后台状态查询挂起不会阻塞文字通知，离开会话后旧增量不再修改页面', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    vi.mocked(getInvocationStatus).mockImplementation(
      () => new Promise(() => {
      }),
    );
    const notify = vi.mocked(watchInvocation).mock.calls[0][1];
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 2,
        kind: 'STARTED',
      });
      await notify({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'OUTPUT',
        text: '及时显示',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '及时显示',
    );
    hook.unmount();
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 5,
        kind: 'OUTPUT',
        text: '迟到',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].streamText).toBe(
      '及时显示',
    );
  });

  it('页面显示未完成的回复并按普通文字转义 HTML，失败后仍保留部分输出', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const page = render(
      <ChatPanel
        config={config}
        conversation={conversation}
        dirty={false}
        onUpdated={vi.fn()}
        t={t}
      />,
    );
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    const notify = vi.mocked(watchInvocation).mock.calls[0][1];
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'OUTPUT',
        text: '首字 <script>alert(1)</script>',
      });
    });
    expect(screen.getByLabelText('实时回复').textContent).toBe(
      '首字 <script>alert(1)</script>',
    );
    expect(page.container.querySelector('script')).toBeNull();
    expect(getInvocationResult).not.toHaveBeenCalled();
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'FAILED', 3)),
    );
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 5,
        kind: 'TERMINAL',
      });
    });
    expect(screen.getByLabelText('实时回复').textContent).toContain('首字');
    expect(screen.getByText('已接收部分输出，完整结果尚未可用。')).toBeTruthy();
  });

  it('发送后历史查询挂起时仍立即显示当前回复', async () => {
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    vi.mocked(turnsForChat).mockImplementation(async () => {
      vi.mocked(getConversation).mockReturnValue(new Promise(() => {
      }));
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
    render(
      <ChatPanel
        config={config}
        conversation={conversation}
        dirty={false}
        onUpdated={vi.fn()}
        t={t}
      />,
    );
    const input = screen.getByRole('textbox', {name: t('messageInput')});
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, {target: {value: '你好'}});
    fireEvent.click(screen.getByRole('button', {name: t('send')}));
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls[0][1]({
        executionId: 'invocation-1',
        sequence: 4,
        kind: 'OUTPUT',
        text: '历史还在加载，首段先显示',
      });
    });
    expect(screen.getByLabelText('实时回复').textContent).toBe(
      '历史还在加载，首段先显示',
    );
    expect(getInvocationResult).not.toHaveBeenCalled();
  });

  it('终态先显示回答，继续观察预算；结算事件刷新余额且不重复读取结果或重连', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'RUNNING', 1),
        budgetState: 'RESERVED',
      }),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    const notify = vi.mocked(watchInvocation).mock.calls[0][1];
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'SUCCEEDED', 3),
        budgetState: 'RESERVED',
      }),
    );
    vi.mocked(getBudget).mockResolvedValue(
      reply({...budget, held: '1', available: '99', version: 1}),
    );
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 5,
        kind: 'TERMINAL',
      });
    });
    expect(hook.result.current.invocations['invocation-1'].result).toEqual(
      result('invocation-1'),
    );
    expect(hook.result.current.activeIds).toEqual(['invocation-1']);
    expect(hook.result.current.watching).toBe(true);
    expect(watchInvocation).toHaveBeenCalledOnce();
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'SUCCEEDED', 3),
        budgetState: 'SETTLED',
      }),
    );
    vi.mocked(getBudget).mockResolvedValue(
      reply({...budget, charged: '0.1', available: '99.9', version: 2}),
    );
    await act(async () => {
      await notify({
        executionId: 'invocation-1',
        sequence: 6,
        kind: 'BUDGET_CHANGED',
      });
    });
    expect(hook.result.current.activeIds).toEqual([]);
    expect(
      hook.result.current.invocations['invocation-1'].status?.budgetState,
    ).toBe('SETTLED');
    expect(hook.result.current.budget?.charged).toBe('0.1');
    expect(hook.result.current.budget?.held).toBe('0');
    expect(getInvocationResult).toHaveBeenCalledOnce();
    expect(watchInvocation).toHaveBeenCalledOnce();
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('SSE 断开后低频 HTTP 也能恢复终态后的预算结算', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'SUCCEEDED', 3),
        budgetState: 'RESERVED',
      }),
    );
    vi.mocked(watchInvocation).mockRejectedValue(new TypeError('offline'));
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    vi.useFakeTimers();
    await act(async () => {
      hook.result.current.resume();
    });
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'SUCCEEDED', 3),
        budgetState: 'RELEASED',
      }),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(hook.result.current.activeIds).toEqual([]);
    expect(
      hook.result.current.invocations['invocation-1'].status?.budgetState,
    ).toBe('RELEASED');
    expect(getInvocationResult).toHaveBeenCalledOnce();
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('UNKNOWN 待对账停止观察但保留预留并阻止继续提交', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'RUNNING', 1),
        budgetState: 'RESERVED',
      }),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply({
        ...status('invocation-1', 'UNKNOWN', 3),
        budgetState: 'PENDING_RECONCILIATION',
      }),
    );
    vi.mocked(getBudget).mockResolvedValue(
      reply({...budget, held: '1', available: '99'}),
    );
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls[0][1]({
        executionId: 'invocation-1',
        sequence: 6,
        kind: 'BUDGET_CHANGED',
      });
    });
    expect(hook.result.current.activeIds).toEqual([]);
    expect(hook.result.current.unresolved).toBe(true);
    expect(hook.result.current.budget?.held).toBe('1');
    act(() => hook.result.current.setDraft('下一轮'));
    await act(async () => {
      await hook.result.current.send();
    });
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('断线 10 秒后恢复查询并用已处理序号重连，卸载取消连接', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    let disconnect: () => void = () => {
    };
    vi.mocked(watchInvocation).mockImplementationOnce(
      async (_, _event, {onConnected}) => {
        onConnected?.();
        await new Promise<void>((resolve) => {
          disconnect = resolve;
        });
      },
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    vi.useFakeTimers();
    await act(async () => {
      await vi.mocked(watchInvocation).mock.calls[0][1]({
        executionId: 'invocation-1',
        sequence: 7,
        kind: 'OUTPUT',
      });
      disconnect();
    });
    const reads = vi.mocked(getInvocationStatus).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9999);
    });
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(watchInvocation).toHaveBeenCalledTimes(2);
    expect(vi.mocked(watchInvocation).mock.calls[1][0].afterSequence).toBe(7);
    const signal = vi.mocked(watchInvocation).mock.calls[1][2].signal;
    hook.unmount();
    expect(signal.aborted).toBe(true);
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('SSE 不可用使用 10 秒兜底；401/403 停止自动请求，过期游标只恢复权威快照', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply(status('invocation-1', 'RUNNING', 1)),
    );
    const hook = mount();
    await ready(hook);
    await waitFor(() => expect(watchInvocation).toHaveBeenCalledOnce());
    vi.useFakeTimers();
    vi.mocked(watchInvocation).mockRejectedValue(new TypeError('offline'));
    await act(async () => {
      hook.result.current.resume();
    });
    const reads = vi.mocked(getInvocationStatus).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9999);
    });
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(getInvocationStatus).toHaveBeenCalledTimes(reads + 1);
    vi.mocked(watchInvocation).mockRejectedValue(
      new AiApiError(403, {code: 'FORBIDDEN'}),
    );
    await act(async () => {
      hook.result.current.resume();
    });
    expect(hook.result.current.watching).toBe(false);
    const stopped = vi.mocked(getInvocationStatus).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(getInvocationStatus).toHaveBeenCalledTimes(stopped);
    vi.mocked(watchInvocation).mockRejectedValue(
      new AiApiError(410, {code: 'CURSOR_EXPIRED'}),
    );
    await act(async () => {
      hook.result.current.resume();
    });
    const streams = vi.mocked(watchInvocation).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(vi.mocked(getInvocationStatus).mock.calls.length).toBeGreaterThan(
      stopped,
    );
    expect(watchInvocation).toHaveBeenCalledTimes(streams);
    expect(turnsForChat).not.toHaveBeenCalled();
  });

  it('LENGTH 显示输出额度不足的原因和操作提示，并保留部分结果，不自动续写', async () => {
    vi.mocked(queryTurnsOfConversation).mockResolvedValue(history([turn()]));
    vi.mocked(getInvocationStatus).mockResolvedValue(
      reply<InvocationStatusResponse>({
        ...status('invocation-1', 'FAILED', 3),
        resultAvailable: true,
        partial: true,
        error: {
          code: 'MODEL_OUTPUT_INCOMPLETE',
          phase: 'OUTPUT',
          retryable: false,
          sideEffect: 'CONFIRMED',
          certainty: 'KNOWN',
          correlationId: 'trace-1',
        },
      }),
    );
    const partial = result('invocation-1');
    if (partial.kind === 'GENERATION') {
      partial.result.value.complete = false;
      partial.result.value.finishReason = 'LENGTH';
      partial.result.value.usage.outputTokens = 512;
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
    expect(
      await screen.findByText(t('outputLimitReached')),
    ).toBeInTheDocument();
    expect(screen.getByText(t('outputLimitHint'))).toBeInTheDocument();
    expect(
      screen.getByText('回复 <script>alert(1)</script>'),
    ).toBeInTheDocument();
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
