import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
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

// A shared selection per account and browser tab. Other tabs never force this tab to switch.
const selections = new Map<string, api.WorkspaceView | null>();
const listeners = new Set<() => void>();

function publish(userId: string, value: api.WorkspaceView | null) {
  selections.set(userId, value);
  listeners.forEach(listener => listener());
}

export function useCurrentWorkspace(userId: string) {
  return useSyncExternalStore(listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => selections.get(userId) ?? null, () => null);
}

const keyFor = (userId: string, kind: string) => `arte.ainew.${kind}.v1.${encodeURIComponent(JSON.stringify([userId]))}`;
type Pending =
  | { kind: 'initialize'; key: string }
  | { kind: 'create'; key: string; request: Parameters<typeof api.createWorkspace>[0] }
  | { kind: 'preference'; key: string; request: Parameters<typeof api.updateWorkspacePreference>[0] };
const scopeSchema = z.object({tenantId: z.string().min(1).max(256), workspaceId: z.string().min(1).max(256)});
const pendingSchema = z.discriminatedUnion('kind', [
  z.object({kind: z.literal('initialize'), key: z.string().min(1).max(256)}),
  z.object({
    kind: z.literal('create'),
    key: z.string().min(1).max(256),
    request: z.object({
      tenantId: z.string().min(1).max(256),
      name: z.string().min(1).max(100),
      description: z.string().max(1000)
    })
  }),
  z.object({
    kind: z.literal('preference'),
    key: z.string().min(1).max(256),
    request: z.object({
      expectedPreferenceVersion: z.number().int().nonnegative(),
      startupScope: scopeSchema.nullable(),
      lastScope: scopeSchema.nullable(),
      favorites: z.array(scopeSchema).max(100)
    })
  }),
]);

export function useWorkspaceDirectory(userId: string) {
  const current = useCurrentWorkspace(userId);
  const [preference, setPreference] = useState<api.WorkspacePreference | null>(null);
  const [records, setRecords] = useState<api.WorkspaceSummary[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [recovery] = useState(() => {
    const stored = readValue<Pending>(keyFor(userId, 'workspaceOperation'));
    const parsed = pendingSchema.safeParse(stored.value);
    return {
      value: parsed.success ? parsed.data : null,
      warning: stored.warning ?? (stored.value && !parsed.success ? RECOVERY_WARNING : null)
    };
  });
  const [pending, setPending] = useState<Pending | null>(recovery.value);
  useEffect(() => {
    if (recovery.warning) setWarning(recovery.warning);
  }, []);
  const pendingRef = useRef(pending);
  const read = useRef<AbortController | null>(null);
  const selectionRead = useRef<AbortController | null>(null);
  const write = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const selectionRevision = useRef(0);
  const [canCreate, setCanCreate] = useState(false);

  function persist(value: Pending | null) {
    pendingRef.current = value;
    setPending(value);
    if (!(value ? writeValue(keyFor(userId, 'workspaceOperation'), value) : removeValue(keyFor(userId, 'workspaceOperation')))) setWarning(STORAGE_WARNING);
  }

  async function select(scope: api.WorkspaceScope) {
    selectionRevision.current++;
    selectionRead.current?.abort();
    const controller = new AbortController();
    selectionRead.current = controller;
    setError(null);
    try {
      const {body} = await api.getWorkspace(scope, {signal: controller.signal});
      if (!alive.current || controller.signal.aborted) return;
      publish(userId, body.data);
      if (!writeValue(keyFor(userId, 'currentWorkspace'), scope)) setWarning(STORAGE_WARNING);
    } catch (failure) {
      if (alive.current && !controller.signal.aborted) setError(failure);
    }
  }

  async function load(currentPage = 1) {
    read.current?.abort();
    const controller = new AbortController();
    read.current = controller;
    setLoading(true);
    setError(null);
    const selectionAtStart = selectionRevision.current;
    try {
      const [prefs, list] = await Promise.all([
        api.getWorkspacePreference({signal: controller.signal}),
        api.listAccessibleWorkspaces(currentPage, {signal: controller.signal}),
      ]);
      if (!alive.current || controller.signal.aborted) return;
      setPreference(prefs.body.data);
      setRecords(list.body.records);
      setPage(list.body.current);
      setTotal(list.body.total);
      const stored = readValue<api.WorkspaceScope>(keyFor(userId, 'currentWorkspace'));
      const parsedScope = scopeSchema.safeParse(stored.value);
      if (stored.warning || (stored.value && !parsedScope.success)) setWarning('保存的空间选择无法读取，已重新校验启动或默认空间。');
      const preferred = selections.get(userId)?.scope ?? (parsedScope.success ? parsedScope.data : null) ?? prefs.body.data.startupScope ?? prefs.body.data.lastScope ?? prefs.body.data.defaultScope;
      try {
        const view = await api.getWorkspace(preferred, {signal: controller.signal});
        if (alive.current && !controller.signal.aborted && selectionAtStart === selectionRevision.current) {
          publish(userId, view.body.data);
          if (!writeValue(keyFor(userId, 'currentWorkspace'), view.body.data.scope)) setWarning(STORAGE_WARNING);
        }
      } catch (failure) {
        if (!(failure instanceof AiApiError) || ![403, 404].includes(failure.httpStatus)) throw failure;
        const fallback = await api.getWorkspace(prefs.body.data.defaultScope, {signal: controller.signal});
        if (alive.current && !controller.signal.aborted && selectionAtStart === selectionRevision.current) {
          publish(userId, fallback.body.data);
          if (!writeValue(keyFor(userId, 'currentWorkspace'), fallback.body.data.scope)) setWarning(STORAGE_WARNING);
          setWarning('原空间已不可访问，已回到个人默认空间。');
        }
      }
    } catch (failure) {
      if (alive.current && !controller.signal.aborted) setError(failure);
    } finally {
      if (alive.current && !controller.signal.aborted) setLoading(false);
    }
  }

  async function execute(operation: Pending) {
    if (write.current || recovery.warning) {
      if (recovery.warning) setWarning(recovery.warning);
      return;
    }
    persist(operation);
    const controller = new AbortController();
    write.current = controller;
    setSaving(true);
    setError(null);
    try {
      if (operation.kind === 'initialize') {
        const response = await api.initializePersonalWorkspace(operation.key, {signal: controller.signal});
        if (controller.signal.aborted || !alive.current) return;
        setPreference(response.body.data.value.preference);
        publish(userId, response.body.data.value.workspace);
      } else if (operation.kind === 'create') {
        const response = await api.createWorkspace(operation.request, operation.key, {signal: controller.signal});
        if (controller.signal.aborted || !alive.current) return;
        publish(userId, response.body.data.value);
        writeValue(keyFor(userId, 'currentWorkspace'), response.body.data.value.scope);
      } else {
        const response = await api.updateWorkspacePreference(operation.request, operation.key, {signal: controller.signal});
        if (controller.signal.aborted || !alive.current) return;
        setPreference(response.body.data.value);
      }
      persist(null);
      await load(page);
    } catch (failure) {
      if (alive.current && !controller.signal.aborted) {
        setError(failure);
        if (failure instanceof AiApiError && failure.httpStatus >= 400 && failure.httpStatus < 500 && failure.httpStatus !== 408 && failure.code !== '109') {
          persist(null);
          if (failure.httpStatus === 409) {
            setWarning('空间或偏好版本已更新，请检查刷新后的内容再操作。');
            await load(page);
          }
        }
      }
    } finally {
      if (alive.current && !controller.signal.aborted) {
        write.current = null;
        setSaving(false);
      }
    }
  }

  useEffect(() => {
    alive.current = true;
    void load();
    const logout = (event: Event) => {
      if ((event as CustomEvent).detail !== userId) return;
      alive.current = false;
      read.current?.abort();
      selectionRead.current?.abort();
      write.current?.abort();
      publish(userId, null);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      alive.current = false;
      read.current?.abort();
      selectionRead.current?.abort();
      write.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId]);
  useEffect(() => {
    setCanCreate(false);
    if (!current) return;
    const controller = new AbortController();
    void api.checkWorkspaceAccess({tenantId: current.scope.tenantId}, 'tenant', 'tenant:workspace:create', {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted) setCanCreate(body.data.allowed);
      }).catch(() => {
      });
    return () => controller.abort();
  }, [current?.scope.tenantId]);
  return {
    current, records, page, total, preference, loading, saving, error, warning, pending, canCreate, load, select,
    initialize: () => !pendingRef.current && execute({kind: 'initialize', key: crypto.randomUUID()}),
    retry: () => pendingRef.current && execute(pendingRef.current),
    create: (name: string, description: string) => current && !pendingRef.current && execute({
      kind: 'create',
      key: crypto.randomUUID(),
      request: {tenantId: current.scope.tenantId, name, description}
    }),
    updatePreference: (change: Partial<Pick<api.WorkspacePreference, 'favorites' | 'startupScope' | 'lastScope'>>) => preference && !pendingRef.current && execute({
      kind: 'preference', key: crypto.randomUUID(), request: {
        expectedPreferenceVersion: preference.version,
        startupScope: preference.startupScope,
        lastScope: preference.lastScope,
        favorites: preference.favorites, ...change,
      }
    }),
    favorite: (scope: api.WorkspaceScope) => preference?.favorites.some(item => sameScope(item, scope)) ?? false,
  };
}
