import {useEffect, useRef, useState} from 'react';
import {history} from '@umijs/max';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {getWorkspace, workspaceScopeSchema} from '@/services/platform/workspace';
import {listWorkspaceModelOptions, type ModelSelection} from '@/services/platform/ai';
import {readValue, sameScope, workspaceStorageKey} from './storage';
import {useConfigurationFallback, useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';

export type AiIntent = {
  scope?: WorkspaceScope;
  model?: ModelSelection;
  management?: WorkspaceScope;
  execution?: WorkspaceScope;
  chat?: WorkspaceScope;
  returnTo?: 'chat' | 'models';
  view?: 'authorization';
  focus?: 'budget';
};
const cleanId = (value: string | null): value is string => !!value && value.length <= 256 && value.trim() === value && Array.from(value).every(character => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127);

/** 路由只是定位意图；完整范围、账号及版本均核验，不代入当前空间或最新版本。 */
export function parseAiIntent(search: string, userId: string): AiIntent {
  const params = new URLSearchParams(search);
  const one = (key: string) => {
    if (params.getAll(key).length > 1) throw new Error('重复的定位参数，请重新选择目标。');
    return params.get(key);
  };
  one('tab');
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
  const focus = one('focus');
  if (focus !== null && (focus !== 'budget' || !model || !target || !management || !sameScope(target, execution))) {
    throw new Error('预算定位需要完整的执行空间、管理空间和固定模型版本。');
  }
  return {
    scope: target,
    management,
    execution,
    chat,
    model, ...(returnTo ? {returnTo} : {}), ...(view ? {view} : {}), ...(focus ? {focus} : {})
  };
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
  if (page === 'workspaces' && intent.focus) params.set('focus', intent.focus);
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
    const exact = body.data.items.find(item => item.resourceId === model.resourceId && item.binding.id === model.binding.id
      && item.binding.version === model.binding.version && item.binding.type === 'binding');
    if (exact) return exact;
    const next = body.data.nextCursor;
    if (next && visited.has(next)) throw new Error('候选分页失效，请重新读取。');
    if (next) visited.add(next);
    cursor = next ?? undefined;
  } while (cursor);
  throw new Error('所定位的模型版本不可读、已失效或不属于此资源，请重新选择。');
}

/** Chat source is only a return target if it still agrees with the actual account's selection. */
export function useChatReturn(userId: string, current: WorkspaceScope | undefined, source?: WorkspaceScope) {
  const navigation = useConfigurationNavigation();
  const stored = workspaceScopeSchema.safeParse(readValue<unknown>(workspaceStorageKey(userId, 'currentWorkspace')).value);
  const scope = current ?? (stored.success ? stored.data : undefined);
  const latest = useRef(scope);
  latest.current = scope;
  const request = useRef<AbortController | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const changed = !!source && !sameScope(scope, source);
  useEffect(() => {
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail === userId) request.current?.abort();
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      request.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId]);

  function back() {
    navigation.request(() => {
      if (source && !sameScope(source, latest.current)) {
        setError('原聊天空间已变化，请重新确认当前使用空间。');
        return;
      }
      const target = source ?? latest.current;
      if (!target) {
        history.push('/AI/ChatNew');
        return;
      }
      request.current?.abort();
      const controller = new AbortController();
      request.current = controller;
      setError('');
      setLoading(true);
      void getWorkspace(target, {signal: controller.signal}).then(({body}) => {
        if (controller.signal.aborted) return;
        if (!sameScope(body.data.scope, target) || !sameScope(latest.current, target))
          throw new Error('聊天空间已变化');
        navigation.request(() => {
          if (!controller.signal.aborted && sameScope(latest.current, target)) history.push('/AI/ChatNew');
        });
      }).catch(() => {
        if (!controller.signal.aborted) setError('原聊天空间不可读取或已变化，请重新确认当前使用空间。');
      }).finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    });
  }

  return {back, error, loading, changed, scope};
}

/** 站内离开与浏览器刷新都保护未保存草稿和原操作；不自动放弃或重发。 */
export function useAiLeaveGuard(unsafe: boolean, onBlocked: () => void) {
  const navigation = useConfigurationNavigation();
  useConfigurationFallback(unsafe, 'legacy-ai', '当前使用页面的未完成操作');
  useEffect(() => {
    if (!unsafe || navigation.available) return;
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
  }, [unsafe, onBlocked, navigation.available]);
}
