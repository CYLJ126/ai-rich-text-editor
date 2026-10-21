import type {RunAnswer, SubmitToolChatRequest, ToolChatStatus,} from '@/services/arte-ai/toolChat';

export const toolChatContext = {
  userId: 'tool-chat-user',
  scope: {tenantId: 'tenant', workspaceId: 'workspace'},
};
export const toolChatRequest: SubmitToolChatRequest = {
  scope: toolChatContext.scope,
  conversationId: 'conversation',
  expectedVersion: 0,
  text: '统计这段文字',
  binding: {type: 'binding', id: 'model', version: 'v1'},
  capability: {type: 'capability', id: 'text', version: 'v1'},
  modelBudgetRef: 'wallet',
  maxInputTokens: 12000,
  generationOptions: {maxOutputTokens: 128, stopSequences: []},
  timeoutSeconds: 120,
  toolBindingIds: ['count'],
  maxModelRounds: 8,
  maxToolCalls: 16,
  maxConcurrentTools: 2,
  budgetLimits: [{currency: 'CNY', amount: '0.123456789012345678'}],
};
export const toolChatStatus: ToolChatStatus = {
  runId: 'run',
  state: 'ACCEPTED',
  version: 0,
  checkpoint: {roundIndex: 0, phase: 'READY_MODEL', children: []},
  modelSteps: 0,
  toolSteps: 0,
  resultAvailable: false,
  cancellationRequested: false,
  error: null,
  acceptedAt: '2026-10-20T12:00:00Z',
  updatedAt: '2026-10-20T12:00:00Z',
};
export const toolChatAnswer: RunAnswer = {
  runId: 'run',
  finalInvocationId: 'final-child',
  result: {
    value: {
      resultId: 'result',
      model: {providerId: 'fixture', modelId: 'model', revision: null},
      outputs: [
        {
          messageId: 'answer-message',
          role: 'ASSISTANT',
          content: [{text: '这段文字共 7 个字符。'}],
          toolCalls: [],
          toolCallId: null,
        },
      ],
      finishReason: 'STOP',
      complete: true,
      structuredOutput: null,
      usage: {
        basis: 'PROVIDER_REPORTED',
        inputTokens: 100,
        outputTokens: 10,
        totalTokens: 110,
      },
      sources: [],
    },
  },
};
export const toolChatReply = <T>(data: T, httpStatus = 200) => ({
  httpStatus,
  body: {success: true as const, code: '200', desc: 'OK', data},
});
