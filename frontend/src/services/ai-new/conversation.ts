import {z} from 'zod';
import type {Conversation, WorkspaceSelection} from '@/types/ai-new/conversation';
import {requestAiNew} from './request';
import {conversationSchema, readContract} from './contracts';

const selection = (scope: WorkspaceSelection) => ({tenantId: scope.tenantId, workspaceId: scope.workspaceId});
const path = (id: string) => `/conversations/${encodeURIComponent(id)}`;

export async function listConversations(scope: WorkspaceSelection, title: string, offset: number, limit: number, signal?: AbortSignal): Promise<Conversation[]> {
  return readContract(z.array(conversationSchema), await requestAiNew('/conversations', {
    params: {...selection(scope), title: title || undefined, offset, limit}, signal,
  }));
}

export async function getConversation(scope: WorkspaceSelection, id: string, signal?: AbortSignal): Promise<Conversation> {
  return readContract(conversationSchema, await requestAiNew(path(id), {params: {...selection(scope)}, signal}));
}

export async function createConversation(scope: WorkspaceSelection, title: string): Promise<Conversation> {
  return readContract(conversationSchema, await requestAiNew('/conversations', {
    method: 'POST',
    data: {...selection(scope), title}
  }));
}

export async function renameConversation(scope: WorkspaceSelection, conversation: Conversation, title: string): Promise<Conversation> {
  return readContract(conversationSchema, await requestAiNew(path(conversation.conversationId), {
    method: 'PATCH', data: {...selection(scope), expectedVersion: conversation.version, title},
  }));
}

export async function deleteConversation(scope: WorkspaceSelection, conversation: Conversation): Promise<Conversation> {
  return readContract(conversationSchema, await requestAiNew(path(conversation.conversationId), {
    method: 'DELETE', params: {...selection(scope), expectedVersion: conversation.version},
  }));
}
