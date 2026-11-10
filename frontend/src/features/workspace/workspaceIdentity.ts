import type {WorkspaceView} from '@/services/platform/workspace';

export function workspaceOwnership(space: Pick<WorkspaceView, 'identity'>) {
  const value = space.identity;
  if (!value) return '空间归属信息待更新';
  return `${value.tenantType === 0 ? value.hostedByMe ? '本人托管' : '个人托管' : '组织'} · ${value.tenantName}`;
}

export function workspaceRelationship(space: Pick<WorkspaceView, 'identity'>) {
  return space.identity ? space.identity.createdByMe ? '本人创建' : '已加入' : '';
}
