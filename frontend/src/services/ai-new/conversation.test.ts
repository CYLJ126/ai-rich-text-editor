import type {Conversation} from '@/types/ai-new/conversation';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import * as api from './conversation';
import {getChatBootstrap} from './bootstrap';
import {AiNewApiError, normalizeApiError} from './request';

vi.mock('@umijs/max', () => ({request: vi.fn()}));

const scope = {tenantId: 'tenant-real', workspaceId: 'workspace-real'};
const conversation: Conversation = {
  conversationId: 'conversation/1',
  scope: {...scope, principal: {principalId: '7', type: 'USER'}},
  title: '测试',
  modelBindingRef: {definitionType: 'ai-binding', definitionId: 'default-model', version: 'v1'},
  status: 'ACTIVE',
  version: 4,
  resources: [],
  createdAt: '2026-10-02T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
  deletedAt: null
};
beforeEach(() => {
  vi.mocked(request).mockReset();
});
describe('new chat HTTP contracts', () => {
  it('reads a direct value response and preserves versions on commands', async () => {
    vi.mocked(request).mockResolvedValue(conversation);
    expect((await api.createConversation(scope, '测试')).conversationId).toBe('conversation/1');
    expect(request).toHaveBeenLastCalledWith('/arte/api/ai-new/conversations', expect.objectContaining({
      method: 'POST',
      data: {...scope, title: '测试'},
      skipErrorHandler: true
    }));
    await api.renameConversation(scope, conversation, '新标题');
    expect(request).toHaveBeenLastCalledWith('/arte/api/ai-new/conversations/conversation%2F1', expect.objectContaining({
      method: 'PATCH',
      data: {...scope, expectedVersion: 4, title: '新标题'}
    }));
    await api.deleteConversation(scope, conversation);
    expect(request).toHaveBeenLastCalledWith('/arte/api/ai-new/conversations/conversation%2F1', expect.objectContaining({
      method: 'DELETE',
      params: {...scope, expectedVersion: 4}
    }));
  });
  it('keeps scope, literal title, pagination and cancellation in reads', async () => {
    vi.mocked(request).mockResolvedValue([]);
    const controller = new AbortController();
    expect(await api.listConversations(scope, '100%', 20, 21, controller.signal)).toEqual([]);
    expect(request).toHaveBeenCalledWith('/arte/api/ai-new/conversations', expect.objectContaining({
      signal: controller.signal,
      params: {...scope, title: '100%', offset: 20, limit: 21}
    }));
  });
  it('rejects legacy envelopes and unsafe numeric versions', async () => {
    vi.mocked(request).mockResolvedValue({success: true, data: conversation});
    await expect(api.createConversation(scope, '测试')).rejects.toMatchObject({status: 502});
    vi.mocked(request).mockResolvedValue({...conversation, version: Number.MAX_SAFE_INTEGER + 1});
    await expect(api.getConversation(scope, '1')).rejects.toBeInstanceOf(AiNewApiError);
  });
  it('supports disabled bootstrap without fabricating workspaces', async () => {
    vi.mocked(request).mockResolvedValue({
      enabled: false,
      unavailableReason: 'CHAT_DISABLED',
      workspaces: [],
      defaultModel: null
    });
    expect((await getChatBootstrap()).workspaces).toEqual([]);
  });
  it('retains the base error facts while discarding arbitrary transport text', async () => {
    const facts = {
      code: 'arte.common.version_conflict',
      failureStage: 'chat-store',
      retryable: false,
      sideEffectStatus: 'NONE',
      resultCertainty: 'CONFIRMED'
    };
    vi.mocked(request).mockRejectedValue({
      message: 'sensitive provider body',
      response: {status: 409, data: {...facts, message: 'sensitive provider body'}}
    });
    const error = await api.renameConversation(scope, conversation, '测试').catch((value) => value);
    expect(error).toBeInstanceOf(AiNewApiError);
    expect(error.status).toBe(409);
    expect(error.facts).toEqual(facts);
    expect(JSON.stringify(normalizeApiError({
      response: {
        status: 500,
        data: {message: 'SECRET'}
      }
    }))).not.toContain('SECRET');
    expect(normalizeApiError({
      response: {
        status: 503,
        data: {...facts, sideEffectStatus: 'OCCURRED'}
      }
    }).facts?.sideEffectStatus).toBe('OCCURRED');
  });
});
