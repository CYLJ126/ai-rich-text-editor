import type {WorkspaceSelection} from '@/types/ai-new/conversation';

export const chatKeys = {
  all: ['ai-new'] as const,
  user: (userId: string) => ['ai-new', userId] as const,
  bootstrap: (userId: string) =>
    [...chatKeys.user(userId), 'bootstrap'] as const,
  scope: (userId: string, scope: WorkspaceSelection) =>
    [...chatKeys.user(userId), scope.tenantId, scope.workspaceId] as const,
  lists: (userId: string, scope: WorkspaceSelection) =>
    [...chatKeys.scope(userId, scope), 'conversations'] as const,
  list: (
    userId: string,
    scope: WorkspaceSelection,
    title: string,
    offset: number,
    limit: number,
  ) => [...chatKeys.lists(userId, scope), {title, offset, limit}] as const,
  detail: (userId: string, scope: WorkspaceSelection, id: string) =>
    [...chatKeys.scope(userId, scope), 'conversation', id] as const,
};
