import {useEffect, useState} from 'react';
import {history} from '@umijs/max';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {workspaceScopeSchema} from '@/services/platform/workspace';
import {listWorkspaceModelOptions, type ModelSelection} from '@/services/platform/ai';

export type AiIntent = {
  scope?: WorkspaceScope;
  model?: ModelSelection;
  management?: WorkspaceScope;
  execution?: WorkspaceScope;
  chat?: WorkspaceScope;
  returnTo?: 'chat' | 'models';
  view?: 'authorization';
};
const cleanId = (value: string | null): value is string => !!value && value.length <= 256 && Array.from(value).every(character => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127);

/** 路由只是定位意图；完整范围、账号及版本均核验，不代入当前空间或最新版本。 */
export function parseAiIntent(search: string, userId: string): AiIntent {
  const params = new URLSearchParams(search);
  const one = (key: string) => {
    if (params.getAll(key).length > 1) throw new Error('重复的定位参数，请重新选择目标。');
    return params.get(key);
  };
  const account = one('account');
  if (account !== null && account !== userId) throw new Error('此定位来自其他账号，请用当前账号重新选择。');
  const scope = (prefix: string) => {
    const tenantId = one(`${prefix}tenantId`), workspaceId = one(`${prefix}workspaceId`);
    if (tenantId === null && workspaceId === null) return undefined;
    if (!cleanId(tenantId) || !cleanId(workspaceId)) throw new Error('空间定位不完整，请重新选择目标。');
    return workspaceScopeSchema.parse({tenantId, workspaceId});
  };
  const target = scope(''), management = scope('management-'), chat = scope('chat-'), execution = scope('execution-');
  const resourceId = one('resourceId'), id = one('bindingId'), version = one('bindingVersion');
  let model: ModelSelection | undefined;
  if (resourceId !== null || id !== null || version !== null) {
    if (!cleanId(resourceId) || !cleanId(id) || !cleanId(version) || version.toLowerCase() === 'latest') throw new Error('模型与精确版本必须完整提供。');
    model = {resourceId, binding: {type: 'binding', id, version}};
  }
  const returnTo = one('returnTo');
  if (returnTo !== null && returnTo !== 'chat' && returnTo !== 'models') throw new Error('返回位置无效，请重新打开配置入口。');
  const view = one('view');
  if (view !== null && view !== 'authorization') throw new Error('模型视图无效。');
  if (target && management && target.tenantId !== management.tenantId) throw new Error('不能将模型定位到其他租户。');
  if (execution && target && execution.tenantId !== target.tenantId) throw new Error('执行空间与模型管理范围不属于同一租户。');
  return {scope: target, management, execution, chat, model, ...(returnTo ? {returnTo} : {}), ...(view ? {view} : {})};
}

export function aiLocation(page: 'workspaces' | 'models', userId: string, intent: AiIntent): string {
  const params = new URLSearchParams({account: userId, tab: page === 'workspaces' ? 'ai' : 'models'});
  const add = (prefix: string, scope?: WorkspaceScope) => {
    if (scope) {
      params.set(`${prefix}tenantId`, scope.tenantId);
      params.set(`${prefix}workspaceId`, scope.workspaceId);
    }
  };
  add('', intent.scope);
  add('management-', intent.management);
  add('chat-', intent.chat);
  add('execution-', intent.execution);
  if (intent.model) {
    params.set('resourceId', intent.model.resourceId);
    params.set('bindingId', intent.model.binding.id);
    params.set('bindingVersion', intent.model.binding.version);
  }
  if (intent.returnTo) params.set('returnTo', intent.returnTo);
  if (intent.view) params.set('view', intent.view);
  return `/Environment/${page === 'workspaces' ? 'Workspaces' : 'Models'}?${params}`;
}

export function useAiRoute() {
  const [search, setSearch] = useState(() => history.location?.search ?? window.location.search);
  useEffect(() => history.listen?.(() => setSearch(history.location?.search ?? window.location.search)), []);
  return search;
}

/** 按服务端可读候选逐页验证精确资源；不存在或撤权均拒绝，不替换版本。 */
export async function verifyAiModel(scope: WorkspaceScope, model: ModelSelection, signal: AbortSignal) {
  let cursor: string | undefined;
  const visited = new Set<string>();
  do {
    const {body} = await listWorkspaceModelOptions(scope, cursor, {signal});
    if (signal.aborted) throw new Error('定位已取消。');
    if (body.data.items.some(item => item.resourceId === model.resourceId && item.binding.id === model.binding.id
      && item.binding.version === model.binding.version && item.binding.type === 'binding')) return;
    const next = body.data.nextCursor;
    if (next && visited.has(next)) throw new Error('候选分页失效，请重新读取。');
    if (next) visited.add(next);
    cursor = next ?? undefined;
  } while (cursor);
  throw new Error('所定位的模型版本不可读、已失效或不属于此资源，请重新选择。');
}

/** 站内离开与浏览器刷新都保护未保存草稿和原操作；不自动放弃或重发。 */
export function useAiLeaveGuard(unsafe: boolean, onBlocked: () => void) {
  useEffect(() => {
    if (!unsafe) return;
    const unblock = history.block?.(() => onBlocked());
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => {
      unblock?.();
      window.removeEventListener('beforeunload', warn);
    };
  }, [unsafe, onBlocked]);
}
