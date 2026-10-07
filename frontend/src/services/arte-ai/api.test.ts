import {request} from '@umijs/max';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {
  AiApiError,
  createConversation,
  getBudget,
  getConversation,
  getInvocationResult,
  getInvocationStatus,
  invocationEvent,
  listConversations,
  queryTurnsOfConversation,
  turnsForChat,
} from './index';
import type {SubmitChatRequest} from './types';

vi.mock('@umijs/max', () => ({request: vi.fn()}));

// Umi 的最后一个重载只接收 URL；显式声明测试使用的双参数调用。
const requestMock = vi.mocked(
  request as (
    url: string,
    options: {
      signal?: AbortSignal;
      validateStatus?: (status: number) => boolean;
    },
  ) => Promise<unknown>,
);

const scope = {tenantId: 'tenant-1', workspaceId: 'workspace-1'};
const conversationQuery = {scope, conversationId: 'conversation-1'};
const invocationQuery = {scope, invocationId: 'invocation-1'};
const replayQuery = {...invocationQuery, afterSequence: 7, limit: 100};
const budgetQuery = {scope, budgetRef: 'budget-1'};
const submit: SubmitChatRequest = {
  ...conversationQuery,
  expectedVersion: 3,
  text: '你好',
  capability: {type: 'capability', id: 'text-generation', version: 'v1'},
  binding: {type: 'binding', id: 'default-binding', version: 'v1'},
  budgetRef: 'budget-1',
  maxInputTokens: 4096,
  generationOptions: {
    maxOutputTokens: 512,
    temperature: null,
    topP: null,
    stopSequences: [],
  },
  timeoutSeconds: 60,
};
const metadata = {success: true, code: '000000', desc: '成功'};
const conversation = {
  conversationId: 'conversation-1',
  title: '测试会话',
  version: 3,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-07T08:00:00Z',
  updatedAt: '2026-10-07T08:00:00Z',
};
const accepted = {
  invocationId: 'invocation-1',
  conversationId: 'conversation-1',
  kind: 'INVOCATION',
  acceptedAt: '2026-10-07T08:00:00Z',
};
const page = {
  ...metadata,
  records: [conversation],
  current: 2,
  size: 20,
  total: 21,
  pages: 2,
};

beforeEach(() => {
  vi.mocked(request).mockReset();
  vi.mocked(request).mockResolvedValue({
    status: 200,
    data: {...metadata, data: conversation},
  });
});

describe('新 AI 接口封装', () => {
  const createData = {scope, title: '测试会话'};
  const listData = {scope, page: {current: 2, size: 20}};
  const turnsData = {
    ...conversationQuery,
    expectedVersion: 3,
    page: {current: 1, size: 20},
  };
  const cases = [
    {
      path: 'conversation/createConversation',
      data: createData,
      key: 'create-key',
      call: () => createConversation(createData, 'create-key'),
    },
    {
      path: 'conversation/listConversations',
      data: listData,
      call: () => listConversations(listData),
      paged: true,
    },
    {
      path: 'conversation/getConversation',
      data: conversationQuery,
      call: () => getConversation(conversationQuery),
    },
    {
      path: 'conversation/queryTurnsOfConversation',
      data: turnsData,
      call: () => queryTurnsOfConversation(turnsData),
      paged: true,
    },
    {
      path: 'chat/turnsForChat',
      data: submit,
      key: 'submit-key',
      call: () => turnsForChat(submit, 'submit-key'),
    },
    {
      path: 'invocation/getInvocationStatus',
      data: invocationQuery,
      call: () => getInvocationStatus(invocationQuery),
    },
    {
      path: 'invocation/getInvocationResult',
      data: invocationQuery,
      call: () => getInvocationResult(invocationQuery),
    },
    {
      path: 'invocation/invocationEvent',
      data: replayQuery,
      call: () => invocationEvent(replayQuery),
    },
    {
      path: 'budget/getBudget',
      data: budgetQuery,
      call: () => getBudget(budgetQuery),
    },
  ];

  it.each(cases)(
    'POST $path，保留原始参数及按需幂等键',
    async ({path, data, key, call, paged}) => {
      if (paged)
        vi.mocked(request).mockResolvedValue({status: 200, data: page});
      await call();
      expect(request).toHaveBeenCalledExactlyOnceWith(
        `/arte/ai-new/${path}`,
        expect.objectContaining({
          method: 'POST',
          data,
          getResponse: true,
          skipErrorHandler: true,
          headers: {
            'Content-Type': 'application/json',
            ...(key ? {'Idempotency-Key': key} : {}),
          },
        }),
      );
      // 409/410 等响应交由封装解析，而不是丢失业务响应体。
      expect(requestMock.mock.calls[0][1]?.validateStatus?.(409)).toBe(true);
    },
  );

  it('创建返回会话以及 HTTP/业务元数据', async () => {
    const body = {...metadata, data: conversation};
    expect(await createConversation(createData, 'create-key')).toEqual({
      httpStatus: 200,
      body,
    });
  });

  it('HTTP 202 只保留受理回执，不补造轮次或会话版本', async () => {
    const body = {...metadata, data: accepted};
    vi.mocked(request).mockResolvedValue({status: 202, data: body});
    expect(await turnsForChat(submit, 'same-key')).toEqual({
      httpStatus: 202,
      body,
    });
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('分页保留顶层 records、页码、总数及空页', async () => {
    vi.mocked(request).mockResolvedValueOnce({status: 200, data: page});
    expect(await listConversations(listData)).toEqual({
      httpStatus: 200,
      body: page,
    });
    const emptyPage = {...page, records: [], total: 0};
    vi.mocked(request).mockResolvedValueOnce({status: 200, data: emptyPage});
    expect((await listConversations(listData)).body.records).toEqual([]);
  });

  it('生成结果保留空白、部分输出与未知 Token 用量', async () => {
    const result = {
      invocationId: 'invocation-1',
      kind: 'GENERATION',
      result: {
        value: {
          resultId: 'result-1',
          model: {
            providerId: 'provider-1',
            modelId: 'model-1',
            revision: null,
          },
          outputs: [
            {
              messageId: 'message-1',
              role: 'ASSISTANT',
              content: [{text: '  第一行\n第二行  '}],
              toolCalls: [],
              toolCallId: null,
            },
          ],
          complete: false,
          finishReason: 'LENGTH',
          structuredOutput: null,
          usage: {
            basis: 'UNKNOWN',
            inputTokens: null,
            outputTokens: null,
            totalTokens: null,
          },
          sources: [],
        },
      },
    };
    vi.mocked(request).mockResolvedValue({
      status: 200,
      data: {...metadata, data: result},
    });
    expect((await getInvocationResult(invocationQuery)).body.data).toEqual(
      result,
    );
  });

  it('事件空页保留原游标，状态 UNKNOWN 保持未知', async () => {
    const events = {
      invocationId: 'invocation-1',
      events: [],
      nextCursor: {executionId: 'invocation-1', afterSequence: 7},
      retainedAfterSequence: 0,
    };
    vi.mocked(request).mockResolvedValueOnce({
      status: 200,
      data: {...metadata, data: events},
    });
    expect((await invocationEvent(replayQuery)).body.data).toEqual(events);
    const status = {
      invocationId: 'invocation-1',
      state: 'UNKNOWN',
      resultAvailable: false,
      partial: null,
    };
    vi.mocked(request).mockResolvedValueOnce({
      status: 200,
      data: {...metadata, data: status},
    });
    expect((await getInvocationStatus(invocationQuery)).body.data.state).toBe(
      'UNKNOWN',
    );
  });

  it('预算金额保持十进制字符串，包括负余额', async () => {
    const budget = {
      budgetRef: 'budget-1',
      currency: 'USD',
      limit: '9999999999999999.123456789',
      held: '1.00',
      charged: '9999999999999999.123456789',
      available: '-1.00',
      rateVersion: {type: 'rate', id: 'default', version: 'v1'},
      version: 2,
    };
    vi.mocked(request).mockResolvedValue({
      status: 200,
      data: {...metadata, data: budget},
    });
    expect((await getBudget(budgetQuery)).body.data).toEqual(budget);
  });

  it('透传 AbortSignal，取消后原样抛出并且不重试', async () => {
    const controller = new AbortController();
    const cancelled = new Error('canceled');
    cancelled.name = 'CanceledError';
    vi.mocked(request).mockRejectedValue(cancelled);
    await expect(
      getInvocationStatus(invocationQuery, {signal: controller.signal}),
    ).rejects.toBe(cancelled);
    expect(request).toHaveBeenCalledTimes(1);
    expect(requestMock.mock.calls[0][1]?.signal).toBe(controller.signal);
  });
});

describe('失败处理', () => {
  it.each([
    [200, '205024'],
    [400, '205025'],
    [401, '000001'],
    [403, '000001'],
    [404, '205029'],
    [409, '205030'],
    [409, '205041'],
    [410, '205037'],
    [500, '000001'],
  ])('保留 HTTP %s 及业务 code=%s', async (httpStatus, code) => {
    const body = {success: false, code, desc: '后端错误说明', data: null};
    vi.mocked(request).mockResolvedValue({status: httpStatus, data: body});
    const error = await getInvocationResult(invocationQuery).catch(
      (value: unknown) => value,
    );
    expect(error).toBeInstanceOf(AiApiError);
    expect(error).toMatchObject({
      httpStatus,
      code,
      desc: body.desc,
      body,
      message: body.desc,
    });
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('HTTP 错误没有标准 JSON 时也保留状态及原始响应', async () => {
    vi.mocked(request).mockResolvedValue({
      status: 502,
      data: '<html>Bad Gateway</html>',
    });
    await expect(getBudget(budgetQuery)).rejects.toMatchObject({
      httpStatus: 502,
      code: undefined,
      body: '<html>Bad Gateway</html>',
    });
  });

  it.each([undefined, {}, '<html>login</html>', {...metadata, data: null}])(
    '拒绝缺失/不合法成功响应 %#',
    async (body) => {
      vi.mocked(request).mockResolvedValue({status: 200, data: body});
      await expect(getConversation(conversationQuery)).rejects.toBeInstanceOf(
        AiApiError,
      );
    },
  );

  it('分页不误读 data 中的 records，也不默认制造空页', async () => {
    vi.mocked(request).mockResolvedValue({
      status: 200,
      data: {...metadata, data: {records: []}},
    });
    await expect(listConversations({scope})).rejects.toMatchObject({
      message: 'Invalid AI API page response',
    });
  });

  it('网络异常原样抛出，不伪造 HTTP 或业务错误码', async () => {
    const networkError = new Error('Network Error');
    vi.mocked(request).mockRejectedValue(networkError);
    await expect(turnsForChat(submit, 'submit-key')).rejects.toBe(networkError);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it.each(['', ' ', 'a'.repeat(257), 'key\r\nX-Test: bad'])(
    '非法幂等键不发请求 %#',
    (key) => {
      expect(() => createConversation({scope, title: '测试'}, key)).toThrow(
        TypeError,
      );
      expect(() => turnsForChat(submit, key)).toThrow(TypeError);
      expect(request).not.toHaveBeenCalled();
    },
  );
});
