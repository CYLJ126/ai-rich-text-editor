import {history} from '@umijs/max';
import type {WorkspaceSelection} from '@/types/ai-new/conversation';

export const scopeValue = (scope: WorkspaceSelection) =>
  JSON.stringify([scope.tenantId, scope.workspaceId]);

export function navigate(scope: WorkspaceSelection, conversationId?: string) {
  const params = new URLSearchParams({
    tenantId: scope.tenantId,
    workspaceId: scope.workspaceId,
  });
  if (conversationId) params.set('conversationId', conversationId);
  history.replace({pathname: '/AI/Chat', search: `?${params}`});
}
