import type {WorkspaceScope} from '@/services/platform/workspace';

export type TenantTab = 'overview' | 'workspaces' | 'policy';

export function readConfigurationLocation(search: string, userId: string) {
  const params = new URLSearchParams(search);
  const one = (key: string) => {
    const values = params.getAll(key);
    if (values.length > 1) throw new Error('定位参数重复，请重新选择配置对象。');
    return values[0];
  };
  const account = one('account');
  if (account !== undefined && account !== userId) throw new Error('此链接属于其他账号，请重新选择配置对象。');
  const id = (key: string) => {
    const value = one(key);
    if (value !== undefined && (!value || value.length > 256 || value.trim() !== value
      || Array.from(value).some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127))) {
      throw new Error('配置对象标识无效，请重新选择。');
    }
    return value;
  };
  const tenantId = id('tenantId'), workspaceId = id('workspaceId'), tab = one('tab');
  const returnTo = one('returnTo');
  if (returnTo !== undefined && returnTo !== 'chat' && returnTo !== 'models')
    throw new Error('配置链接的返回位置无效。');
  if (workspaceId && !tenantId) throw new Error('缺少所属租户，请重新选择工作空间。');
  return {tenantId, workspaceId, tab};
}

export function workspaceConfigurationLocation(userId: string, scope: WorkspaceScope, tab: 'overview' | 'policy') {
  return `/Environment/Workspaces?${new URLSearchParams({account: userId, ...scope, tab})}`;
}

