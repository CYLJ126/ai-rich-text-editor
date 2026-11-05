import {useEffect, useSyncExternalStore} from 'react';
import {z} from 'zod';
import {AiApiError} from '@/services/arte-ai';
import * as api from '@/services/platform/workspace';
import {
  readValue,
  RECOVERY_WARNING,
  removeValue,
  sameScope,
  STORAGE_WARNING,
  writeValue
} from '@/features/ainew/storage';

export const workspaceStorageKey = (userId: string, kind: string) =>
  `arte.ainew.${kind}.v1.${encodeURIComponent(JSON.stringify([userId]))}`;
const key = z.string().min(1).max(256).refine(value => value.trim() === value && Array.from(value).every(character => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127));
const pendingSchema = z.discriminatedUnion('kind', [
  z.object({kind: z.literal('initialize'), key, operationId: key.optional()}),
  z.object({
    kind: z.literal('create'), key, operationId: key.optional(), request: z.object({
      tenantId: z.string().min(1).max(256), name: z.string().min(1).max(100), description: z.string().max(1000),
    })
  }),
  z.object({
    kind: z.literal('rename'), key, operationId: key.optional(), request: z.object({
      scope: api.workspaceScopeSchema, expectedVersion: z.number().int().nonnegative(),
      name: z.string().min(1).max(100), description: z.string().max(1000),
    })
  }),
  z.object({
    kind: z.literal('preference'), key, operationId: key.optional(), request: z.object({
      expectedPreferenceVersion: z.number().int().nonnegative(), startupScope: api.workspaceScopeSchema.nullable(),
      lastScope: api.workspaceScopeSchema.nullable(), favorites: z.array(api.workspaceScopeSchema).max(100),
    })
  }),
]);
export type WorkspaceOperation = z.infer<typeof pendingSchema>;
type SelectionGuard = (scope: api.WorkspaceScope, proceed: () => void) => void;
const guards = new Map<string, Set<SelectionGuard>>();

/** Every picker runs the active module's protection before changing scope. */
export function registerWorkspaceGuard(userId: string, guard: SelectionGuard) {
  const entries = guards.get(userId) ?? new Set<SelectionGuard>();
  entries.add(guard);
  guards.set(userId, entries);
  return () => {
    entries.delete(guard);
    if (!entries.size && guards.get(userId) === entries) guards.delete(userId);
  };
}

export function protectWorkspaceChange(userId: string, scope: api.WorkspaceScope, proceed: () => void) {
  const entries = [...(guards.get(userId) ?? [])];
  const run = (index: number) => index < entries.length ? entries[index](scope, () => run(index + 1)) : proceed();
  run(0);
}

interface DirectoryState {
  current: api.WorkspaceView | null;
  preference: api.WorkspacePreference | null;
  personalTenant: api.TenantView | null;
  records: api.WorkspaceSummary[];
  search: string;
  nextCursor: string | null;
  exhausted: boolean;
  cursorExpired: boolean;
  loading: boolean;
  selecting: boolean;
  saving: boolean;
  loaded: boolean;
  error: unknown;
  warning: string | null;
  pending: WorkspaceOperation | null;
  recoveryBlocked: boolean;
  unsafeToLeave: boolean;
  canCreate: boolean;
  canRename: boolean;
  lastReceipt: api.CommandReceipt<unknown> | null;
}

const empty: DirectoryState = {
  current: null,
  preference: null,
  personalTenant: null,
  records: [],
  search: '',
  nextCursor: null,
  exhausted: false,
  cursorExpired: false,
  loading: false, selecting: false, saving: false, loaded: false, error: null, warning: null,
  pending: null, recoveryBlocked: false, unsafeToLeave: false, canCreate: false, canRename: false, lastReceipt: null,
};
const stores = new Map<string, WorkspaceDirectory>();
const managementStores = new Map<string, WorkspaceDirectory>();

function isHidden(failure: unknown) {
  return failure instanceof AiApiError && [403, 404].includes(failure.httpStatus);
}

function authExpired(failure: unknown) {
  return failure instanceof AiApiError && failure.httpStatus === 401;
}

function validateView(scope: api.WorkspaceScope, view: api.WorkspaceView) {
  if (!api.workspaceViewSchema.safeParse(view).success || !sameScope(scope, view.scope))
    throw new Error('空间响应与请求范围不一致，请重试。');
  return view;
}

/** One directory, preference snapshot and request coordinator per account in this JS/tab runtime. */
class WorkspaceDirectory {
  private state: DirectoryState = {...empty};
  private listeners = new Set<() => void>();
  private reads?: AbortController;
  private selection?: AbortController;
  private write?: AbortController;
  private permissions?: AbortController;
  private revision = 0;
  private stopped = false;
  private consumers = 0;

  constructor(private readonly userId: string, private readonly management = false) {
    const stored = readValue<WorkspaceOperation>(workspaceStorageKey(userId, management ? 'workspaceManagementOperation' : 'workspaceOperation'));
    const parsed = pendingSchema.safeParse(stored.value);
    const warning = stored.warning ?? (stored.value && !parsed.success ? RECOVERY_WARNING : null);
    this.state = {...empty, pending: parsed.success ? parsed.data : null, warning, recoveryBlocked: !!warning};
  }

  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private update(change: Partial<DirectoryState>) {
    this.state = {...this.state, ...change};
    this.listeners.forEach(listener => {
      listener();
    });
  }

  attach = (load = true) => {
    this.consumers++;
    if (this.stopped && !this.state.current) this.update({loaded: false});
    this.stopped = false;
    if (load && !this.state.loaded && !this.state.loading && this.userId) void this.load();
    return () => {
      this.consumers--;
      if (this.consumers > 0) return;
      this.stopped = true;
      this.reads?.abort();
      this.selection?.abort();
      this.write?.abort();
      this.permissions?.abort();
      this.write = undefined;
      this.update({loading: false, selecting: false, saving: false});
    };
  };
  clear = () => {
    this.stopped = true;
    this.revision++;
    this.reads?.abort();
    this.selection?.abort();
    this.write?.abort();
    this.permissions?.abort();
    this.write = undefined;
    this.update({...empty, loaded: true});
  };

  private persist(operation: WorkspaceOperation | null) {
    const saved = operation ? writeValue(workspaceStorageKey(this.userId, this.management ? 'workspaceManagementOperation' : 'workspaceOperation'), operation)
      : removeValue(workspaceStorageKey(this.userId, this.management ? 'workspaceManagementOperation' : 'workspaceOperation'));
    this.update({
      pending: operation, unsafeToLeave: !saved,
      ...(!saved ? {warning: STORAGE_WARNING, recoveryBlocked: !operation} : {})
    });
    return saved;
  }

  private accept(view: api.WorkspaceView, deliberate = false) {
    if (deliberate) this.revision++;
    this.update({current: view, canCreate: false, canRename: false});
    if (!this.management && !writeValue(workspaceStorageKey(this.userId, 'currentWorkspace'), view.scope)) this.update({warning: STORAGE_WARNING});
    void this.checkPermissions(view);
    if (deliberate && !this.management) window.dispatchEvent(new CustomEvent('arte-workspace-selected', {
      detail: {
        userId: this.userId,
        scope: view.scope
      }
    }));
  }

  private async checkPermissions(view: api.WorkspaceView) {
    this.permissions?.abort();
    const controller = new AbortController();
    this.permissions = controller;
    const check = async (type: 'tenant' | 'workspace', action: string) => {
      try {
        const response = await api.checkWorkspaceAccess(type === 'tenant' ? {tenantId: view.scope.tenantId} : view.scope, type, action, {signal: controller.signal});
        return response.body.data.allowed;
      } catch {
        return false;
      }
    };
    const [canCreate, canRename] = await Promise.all([check('tenant', 'tenant:workspace:create'), check('workspace', 'workspace:update')]);
    if (!controller.signal.aborted && !this.stopped && sameScope(this.state.current?.scope, view.scope)) this.update({
      canCreate,
      canRename
    });
  }

  refreshView = (view: api.WorkspaceView) => {
    if (sameScope(this.state.current?.scope, view.scope) && view.version >= this.state.current!.version) {
      this.update({current: view});
      void this.checkPermissions(view);
    }
  };
  refreshCurrentMetadata = async (scope: api.WorkspaceScope) => {
    if (!sameScope(scope, this.state.current?.scope) || this.stopped) return;
    try {
      const {body} = await api.getWorkspace(scope);
      if (!this.stopped && sameScope(scope, this.state.current?.scope)) this.refreshView(validateView(scope, body.data));
    } catch { /* 变更通知不授权读取；原访问控制仍由服务端核验。 */
    }
  };
  savedWorkspace = () => {
    const parsed = api.workspaceViewSchema.safeParse(this.state.lastReceipt?.value);
    return parsed.success ? parsed.data : null;
  };

  select = async (scope: api.WorkspaceScope, onSelected?: () => void, entrySignal?: AbortSignal) => {
    if (entrySignal?.aborted) return false;
    if (this.state.unsafeToLeave || this.state.recoveryBlocked) {
      this.update({warning: '未确认空间操作无法安全恢复，请先处理原操作后再切换。'});
      return false;
    }
    this.revision++;
    this.selection?.abort();
    const controller = new AbortController();
    this.selection = controller;
    const signal = entrySignal ? AbortSignal.any([controller.signal, entrySignal]) : controller.signal;
    this.update({selecting: true, error: null});
    try {
      const response = await api.getWorkspace(scope, {signal});
      if (this.stopped || signal.aborted) return false;
      const view = validateView(scope, response.body.data);
      // Re-run protection at commit: the user may have edited a draft while getWorkspace was in flight.
      let committed = false;
      protectWorkspaceChange(this.userId, scope, () => {
        if (this.stopped || signal.aborted) return;
        this.accept(view, true);
        committed = true;
        onSelected?.();
        if (!this.management && !this.state.pending && !this.write) {
          if (this.state.preference) void this.updatePreference({lastScope: scope});
          else if (entrySignal) {
            const selectedRevision = this.revision;
            // 配置页可在聊天目录尚未挂载时显式进入，仅读取既有偏好。
            void api.getWorkspacePreference({signal}).then(({body}) => {
              if (this.stopped || signal.aborted || this.revision !== selectedRevision
                || this.state.pending || this.write) return;
              this.update({preference: this.state.preference ?? body.data});
              void this.updatePreference({lastScope: scope});
            }).catch(() => { /* 无可用偏好时不初始化，选择结果仍有效。 */
            });
          }
        }
      });
      return committed;
    } catch (failure) {
      if (!this.stopped && !signal.aborted) {
        this.update({error: failure});
        if (authExpired(failure)) this.update({current: null, records: [], canCreate: false, canRename: false});
        else if (isHidden(failure) && sameScope(scope, this.state.current?.scope)) {
          this.update({current: null, canCreate: false, canRename: false});
          await this.load();
        }
      }
      return false;
    } finally {
      if (!this.stopped && this.selection === controller) this.update({selecting: false});
    }
  };
  load = async (search = this.state.search): Promise<void> => {
    this.reads?.abort();
    const controller = new AbortController();
    this.reads = controller;
    const selectionRevision = this.revision;
    this.update({
      loading: true,
      error: null,
      records: [],
      search,
      nextCursor: null,
      exhausted: false,
      cursorExpired: false
    });
    try {
      // A missing preference is the server's indication that this account has not initialized its default space.
      // Never initialize on network/401/403/503 failures or while a previous command is uncertain.
      const stored = this.management ? null : readValue<api.WorkspaceScope>(workspaceStorageKey(this.userId, 'currentWorkspace'));
      const parsed = api.workspaceScopeSchema.safeParse(stored?.value);
      let prefs: Awaited<ReturnType<typeof api.getWorkspacePreference>> | undefined;
      try {
        prefs = await api.getWorkspacePreference({signal: controller.signal});
      } catch (failure) {
        if (failure instanceof AiApiError && failure.httpStatus === 404 && failure.code === 'PLATFORM_NOT_FOUND'
          && !controller.signal.aborted && !this.stopped && !this.state.pending && !this.state.recoveryBlocked && !this.write) {
          if (this.management || this.state.current || parsed.success) {
            this.update({preference: null});
          } else {
            await this.initialize();
            return;
          }
        } else throw failure;
      }
      const list = await api.browseAccessibleWorkspaces(null, search, {signal: controller.signal});
      if (this.stopped || controller.signal.aborted || selectionRevision !== this.revision) return;
      this.update({
        preference: prefs?.body.data ?? null,
        records: list.body.data.records,
        nextCursor: list.body.data.nextCursor,
        exhausted: list.body.data.exhausted,
      });
      if (this.management) return;
      if (stored?.warning || (stored?.value && !parsed.success)) this.update({warning: '保存的空间选择无法读取，已重新校验启动或默认空间。'});
      const preferred = this.state.current?.scope ?? (parsed.success ? parsed.data : null)
        ?? prefs?.body.data.startupScope ?? prefs?.body.data.lastScope ?? prefs?.body.data.defaultScope;
      if (!preferred) throw new Error('当前空间和个人空间偏好均不可用。');
      let view: api.WorkspaceView;
      let fallback = false;
      try {
        const response = await api.getWorkspace(preferred, {signal: controller.signal});
        view = validateView(preferred, response.body.data);
      } catch (failure) {
        if (!isHidden(failure) || this.stopped || controller.signal.aborted || selectionRevision !== this.revision) throw failure;
        this.update({current: null, canCreate: false, canRename: false});
        if (!prefs) {
          if (!this.state.pending && !this.state.recoveryBlocked && !this.write) await this.initialize();
          return;
        }
        const response = await api.getWorkspace(prefs.body.data.defaultScope, {signal: controller.signal});
        view = validateView(prefs.body.data.defaultScope, response.body.data);
        fallback = true;
      }
      if (this.stopped || controller.signal.aborted || selectionRevision !== this.revision) return;
      this.accept(view);
      if (fallback) this.update({warning: '原空间已不可访问，已回到个人默认空间。'});
    } catch (failure) {
      if (!this.stopped && !controller.signal.aborted && selectionRevision === this.revision) {
        this.update({error: failure});
        if (authExpired(failure) || isHidden(failure)) this.update({
          current: null,
          records: [],
          nextCursor: null,
          canCreate: false,
          canRename: false
        });
      }
    } finally {
      if (!this.stopped && !controller.signal.aborted) this.update({loading: false, loaded: true});
    }
  };

  setSearch = async (search: string): Promise<void> => {
    if (!this.state.preference || !this.state.loaded) return this.load(search);
    await this.readDirectory(false, search);
  };

  loadMore = async (): Promise<void> => {
    if (this.state.loading || this.state.exhausted || this.state.cursorExpired || !this.state.nextCursor) return;
    await this.readDirectory(true, this.state.search);
  };

  private async readDirectory(append: boolean, search: string): Promise<void> {
    this.reads?.abort();
    const controller = new AbortController();
    this.reads = controller;
    const cursor = append ? this.state.nextCursor : null;
    this.update({
      loading: true, error: null, ...(!append ? {
        search, records: [], nextCursor: null, exhausted: false, cursorExpired: false,
      } : {})
    });
    try {
      const response = await api.browseAccessibleWorkspaces(cursor, search, {signal: controller.signal});
      if (this.stopped || controller.signal.aborted) return;
      const page = response.body.data;
      if (page.nextCursor !== null && page.nextCursor === cursor) throw new Error('目录未向后推进，请刷新。');
      const records = append ? [...this.state.records, ...page.records] : page.records;
      if (new Set(records.map(record => JSON.stringify([record.scope.tenantId, record.scope.workspaceId]))).size !== records.length)
        throw new Error('目录返回重复空间，请刷新。');
      this.update({records, nextCursor: page.nextCursor, exhausted: page.exhausted});
    } catch (failure) {
      if (!this.stopped && !controller.signal.aborted) {
        this.update({error: failure});
        if (failure instanceof AiApiError && failure.code === 'PLATFORM_CURSOR_EXPIRED')
          this.update({cursorExpired: true});
        if (authExpired(failure)) this.update({
          current: null, records: [], nextCursor: null,
          canCreate: false, canRename: false
        });
      }
    } finally {
      if (!this.stopped && !controller.signal.aborted) this.update({loading: false});
    }
  }

  private async execute(operation: WorkspaceOperation): Promise<boolean> {
    if (this.write || this.state.recoveryBlocked || this.stopped) return false;
    const selectionRevision = this.revision;
    if (!this.persist(operation) && this.management) {
      this.update({recoveryBlocked: true});
      return false;
    }
    const controller = new AbortController();
    this.write = controller;
    this.update({saving: true, error: null});
    try {
      const options = {signal: controller.signal};
      const response = operation.operationId
        ? await api.getWorkspaceCommandReceipt<unknown>(operation.operationId, options)
        : operation.kind === 'initialize' ? await api.initializePersonalWorkspace(operation.key, options)
          : operation.kind === 'create' ? await api.createWorkspace(operation.request, operation.key, options)
            : operation.kind === 'rename' ? await api.renameWorkspace(operation.request, operation.key, options)
              : await api.updateWorkspacePreference(operation.request, operation.key, options);
      if (this.stopped || controller.signal.aborted) return false;
      const receipt = response.body.data;
      if (receipt.status !== 1) throw new Error('空间命令尚未确认完成，请确认原操作。');
      // Retain the server operation ID if follow-up validation fails; receipt queries never execute a new write.
      this.persist({...operation, operationId: receipt.operationId});
      if (operation.kind === 'initialize') {
        const personal = api.personalWorkspaceSchema.parse(receipt.value);
        this.update({preference: personal.preference, personalTenant: personal.tenant});
        if (!this.management && selectionRevision === this.revision) this.accept(personal.workspace);
      } else if (operation.kind === 'preference') {
        this.update({preference: api.workspacePreferenceSchema.parse(receipt.value)});
      } else {
        const view = api.workspaceViewSchema.parse(receipt.value);
        if (operation.kind === 'rename') validateView(operation.request.scope, view);
        else if (view.scope.tenantId !== operation.request.tenantId) throw new Error('创建空间的租户与原请求不一致。');
        if (!this.management && selectionRevision === this.revision && (operation.kind === 'create' || sameScope(this.state.current?.scope, view.scope))) {
          if (operation.kind === 'create') protectWorkspaceChange(this.userId, view.scope, () => {
            if (!this.stopped && selectionRevision === this.revision) this.accept(view, true);
          });
          else this.accept(view);
        }
      }
      if (this.management && operation.kind === 'rename') {
        const view = api.workspaceViewSchema.parse(receipt.value);
        stores.get(this.userId)?.refreshView(view);
        window.dispatchEvent(new CustomEvent('arte-workspace-details-updated', {detail: {userId: this.userId, view}}));
        try {
          localStorage.setItem('arte.workspace.metadata.revision', JSON.stringify({
            userId: this.userId,
            scope: view.scope,
            revision: crypto.randomUUID()
          }));
        } catch { /* 同页更新已完成，跨页可重新读取。 */
        }
      }
      this.update({lastReceipt: receipt});
      this.persist(null);
      await this.load();
      return true;
    } catch (failure) {
      if (!this.stopped && !controller.signal.aborted) {
        this.update({error: failure});
        if (failure instanceof AiApiError && failure.httpStatus >= 400 && failure.httpStatus < 500
          && failure.httpStatus !== 408 && failure.code !== 'PLATFORM_IDEMPOTENCY_CONFLICT') {
          this.persist(null);
          if (authExpired(failure)) this.update({current: null, records: [], canCreate: false, canRename: false});
          if (failure.httpStatus === 409) {
            this.update({warning: '空间或偏好版本已更新，请检查刷新后的内容再操作。'});
            await this.load();
          }
        }
      }
      return false;
    } finally {
      if (this.write === controller) this.write = undefined;
      if (!this.stopped && !controller.signal.aborted) this.update({saving: false});
    }
  }

  initialize = () => this.state.pending ? Promise.resolve(false) : this.execute({
    kind: 'initialize',
    key: crypto.randomUUID()
  });
  retry = () => this.state.pending ? this.execute(this.state.pending) : Promise.resolve(false);
  create = (name: string, description: string, tenantId = this.state.current?.scope.tenantId) => tenantId && !this.state.pending ? this.execute({
    kind: 'create', key: crypto.randomUUID(), request: {tenantId, name, description},
  }) : Promise.resolve(false);
  rename = (view: api.WorkspaceView, name: string, description: string) => !this.state.pending ? this.execute({
    kind: 'rename',
    key: crypto.randomUUID(),
    request: {scope: view.scope, expectedVersion: view.version, name, description},
  }) : Promise.resolve(false);
  updatePreference = (change: Partial<Pick<api.WorkspacePreference, 'favorites' | 'startupScope' | 'lastScope'>>) => {
    const preference = this.state.preference;
    return preference && !this.state.pending ? this.execute({
      kind: 'preference', key: crypto.randomUUID(), request: {
        expectedPreferenceVersion: preference.version, startupScope: preference.startupScope,
        lastScope: preference.lastScope, favorites: preference.favorites, ...change,
      }
    }) : Promise.resolve(false);
  };
  favorite = (scope: api.WorkspaceScope) => this.state.preference?.favorites.some(item => sameScope(item, scope)) ?? false;
}

function directoryFor(userId: string) {
  let directory = stores.get(userId);
  if (!directory) {
    directory = new WorkspaceDirectory(userId);
    stores.set(userId, directory);
  }
  return directory;
}

if (typeof window !== 'undefined') window.addEventListener('storage', event => {
  if (event.key !== 'arte.workspace.metadata.revision' || !event.newValue) return;
  try {
    const change = z.object({
      userId: z.string(),
      scope: api.workspaceScopeSchema,
      revision: z.string()
    }).parse(JSON.parse(event.newValue));
    void stores.get(change.userId)?.refreshCurrentMetadata(change.scope);
  } catch { /* 损坏通知不改变当前空间。 */
  }
});

if (typeof window !== 'undefined') window.addEventListener('arte-account-session-cleared', event => {
  const userId = (event as CustomEvent<string>).detail;
  stores.get(userId)?.clear();
  managementStores.get(userId)?.clear();
  guards.delete(userId);
});

/** 保留公共选择服务供显式“进入”操作使用，挂载时不加载或回退使用空间。 */
export function retainWorkspaceSelection(userId: string) {
  const directory = directoryFor(userId);
  const lifetime = new AbortController();
  const release = directory.attach(false);
  return {
    select: (scope: api.WorkspaceScope, onSelected: () => void) => directory.select(scope, onSelected, lifetime.signal),
    release: () => {
      lifetime.abort();
      release();
    },
  };
}

export function useCurrentWorkspace(userId: string) {
  const directory = directoryFor(userId);
  return useSyncExternalStore(directory.subscribe, () => directory.snapshot().current, () => null);
}

export function useWorkspaceDirectory(userId: string) {
  return useDirectory(directoryFor(userId));
}

/** 独立治理目录：不读取、保存或切换聊天当前空间。 */
export function useWorkspaceManagement(userId: string) {
  let directory = managementStores.get(userId);
  if (!directory) {
    directory = new WorkspaceDirectory(userId, true);
    managementStores.set(userId, directory);
  }
  return useDirectory(directory);
}

function useDirectory(directory: WorkspaceDirectory) {
  const state = useSyncExternalStore(directory.subscribe, directory.snapshot, () => empty);
  useEffect(() => directory.attach(), [directory]);
  useEffect(() => {
    if (!state.unsafeToLeave) return;
    const preventLoss = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', preventLoss);
    return () => window.removeEventListener('beforeunload', preventLoss);
  }, [state.unsafeToLeave]);
  return {
    ...state,
    load: directory.load,
    loadMore: directory.loadMore,
    setSearch: directory.setSearch,
    select: directory.select,
    initialize: directory.initialize,
    retry: directory.retry,
    create: directory.create,
    rename: directory.rename,
    updatePreference: directory.updatePreference,
    favorite: directory.favorite,
    savedWorkspace: directory.savedWorkspace
  };
}
