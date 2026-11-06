import React, {createContext, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {history} from '@umijs/max';
import {Alert, Button, Flex, Modal, Typography} from 'antd';
import {registerWorkspaceGuard} from '@/features/workspace/useWorkspace';

export type ConfigurationStatus = 'clean' | 'dirty' | 'submitting' | 'pending' | 'blocked';
export type ConfigurationDraft = {
  group: string;
  label: string;
  status: ConfigurationStatus;
  /** Only ordinary draft saves belong here; publishing, grants and budget approval stay explicit. */
  save?: () => Promise<boolean>;
  discard?: () => Promise<boolean> | boolean;
  fallback?: boolean;
  order?: number;
};
type Entry = ConfigurationDraft & { id: string; namespace: string };
type Filter = (entry: Entry) => boolean;
type Navigation = { action: () => void; filter?: Filter };
type Coordinator = {
  userId: string;
  namespace: string;
  register: (id: string, read: () => Entry) => () => void;
  changed: () => void;
  request: (action: () => void, filter?: Filter) => void;
};
const Context = createContext<Coordinator | null>(null);
const statusText: Record<ConfigurationStatus, string> = {
  clean: '', dirty: '有未保存修改', submitting: '正在提交，请等待结果',
  pending: '保存结果待确认，请在原操作处检查结果', blocked: '操作暂不能安全恢复，请在原页面处理'
};

/** A single coordinator surrounds cached app pages; nested boundaries isolate local target changes. */
export default function ConfigurationBoundary({userId, children}: { userId: string; children: React.ReactNode }) {
  const parent = useContext(Context);
  const id = useId();
  const local = useMemo(() => parent ? {...parent, namespace: id} : null, [parent, id]);
  if (parent?.userId === userId) return <Context.Provider value={local}>{children}</Context.Provider>;
  return <CoordinatorProvider key={userId} userId={userId}>{children}</CoordinatorProvider>;
}

function CoordinatorProvider({userId, children}: { userId: string; children: React.ReactNode }) {
  const entries = useRef(new Map<string, () => Entry>());
  const [, setRevision] = useState(0);
  const [navigation, setNavigation] = useState<Navigation | null>(null);
  const pending = useRef<Navigation | null>(null);
  const [working, setWorking] = useState(false);
  const writing = useRef(false);
  const [error, setError] = useState('');
  const live = useRef(true);
  const changed = React.useCallback(() => setRevision(value => value + 1), []);
  const active = React.useCallback((filter?: Filter) => {
    const values = [...entries.current.values()].map(read => read());
    return values.filter(entry => entry.status !== 'clean' && (!filter || filter(entry)) && (!entry.fallback
      || !values.some(other => !other.fallback && other.status !== 'clean'
        && other.namespace === entry.namespace && other.group === entry.group)))
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  }, []);
  const request = React.useCallback((action: () => void, filter?: Filter) => {
    if (!live.current || writing.current || pending.current) return;
    if (!active(filter).length) {
      action();
      return;
    }
    const next = {action, filter};
    pending.current = next;
    setError('');
    setNavigation(next);
  }, [active]);
  const value = useMemo<Coordinator>(() => ({
    userId, namespace: '', changed, request,
    register: (id, read) => {
      entries.current.set(id, read);
      changed();
      return () => {
        entries.current.delete(id);
        changed();
      };
    }
  }), [userId, changed, request]);

  useEffect(() => {
    live.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      live.current = false;
      pending.current = null;
      setNavigation(null);
      changed();
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId, changed]);

  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (live.current) request(proceed); else proceed();
  }), [userId, request]);

  const shouldProtect = live.current && active().length > 0;
  useLayoutEffect(() => {
    // Browser history also installs its own beforeunload listener while blocked.
    if (!shouldProtect) return;
    let unblock: (() => void) | undefined;
    let disposed = false;
    const install = () => {
      unblock = history.block?.(transition => {
        const proceed = () => {
          unblock?.();
          unblock = undefined;
          transition.retry();
          if (!disposed && live.current && active().length) install();
        };
        if (live.current) request(proceed); else proceed();
      });
    };
    install();
    const warn = (event: BeforeUnloadEvent) => {
      if (live.current && active().length) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => {
      disposed = true;
      unblock?.();
      unblock = undefined;
      window.removeEventListener('beforeunload', warn);
    };
  }, [shouldProtect, request, active]);

  const selected = navigation ? active(navigation.filter) : [];
  const canDiscard = selected.every(entry => entry.status === 'dirty' && !!entry.discard);
  const canSave = selected.every(entry => entry.status === 'dirty' && !!entry.save);

  async function resolve(kind: 'save' | 'discard') {
    const intent = pending.current;
    if (!intent || writing.current) return;
    writing.current = true;
    setWorking(true);
    setError('');
    try {
      for (const entry of active(intent.filter)) {
        if (!live.current) return;
        const current = entries.current.get(entry.id)?.();
        if (!current || current.status === 'clean') continue;
        const action = current[kind];
        if (current.status !== 'dirty' || !action || !await action()) {
          setError(`“${entry.label}”尚未处理完成，请核对原页面提示。`);
          return;
        }
        // Let React publish updated draft/parent versions before saving the next dependent form.
        await new Promise<void>(done => setTimeout(done, 0));
      }
      if (!live.current) return;
      if (active(intent.filter).length) {
        setError('配置状态已变化，请核对剩余修改或待确认操作后继续。');
        return;
      }
      pending.current = null;
      setNavigation(null);
      writing.current = false;
      setWorking(false);
      intent.action();
    } catch {
      if (live.current) setError('处理未完成，当前页面和修改已保留，请检查具体操作提示。');
    } finally {
      writing.current = false;
      if (live.current) setWorking(false);
    }
  }

  return <Context.Provider value={value}>
    {children}
    <Modal title="处理当前配置后继续" open={!!navigation} footer={null}
           closable={!working} keyboard={!working} mask={{closable: !working}}
           onCancel={() => {
             if (!working) {
               pending.current = null;
               setNavigation(null);
             }
           }}>
      <Flex vertical gap={12}>
        {selected.map(entry => <Typography.Text
          key={entry.id}>{entry.label}：{statusText[entry.status]}</Typography.Text>)}
        {selected.some(entry => entry.status === 'dirty' && !entry.save) && <Typography.Paragraph type="secondary">
          发布、密钥提交、授权和预算批准请在原配置中明确操作；此处可以放弃尚未提交的修改。
        </Typography.Paragraph>}
        {error && <Alert type="warning" title={error}/>}
        <Flex gap={8} wrap>
          <Button type="primary" disabled={!canSave} loading={working}
                  onClick={() => void resolve('save')}>保存并继续</Button>
          <Button disabled={!canDiscard || working} onClick={() => void resolve('discard')}>放弃修改并继续</Button>
          <Button disabled={working} onClick={() => {
            pending.current = null;
            setNavigation(null);
          }}>留在本页</Button>
        </Flex>
      </Flex>
    </Modal>
  </Context.Provider>;
}

export function useConfigurationNavigation() {
  const context = useContext(Context);
  return useMemo(() => ({
    available: !!context,
    request: (action: () => void, groups?: string[]) => {
      if (!context) {
        action();
        return;
      }
      context.request(action, context.namespace || groups ? entry => (!context.namespace || entry.namespace === context.namespace)
        && (!groups || groups.includes(entry.group)) : undefined);
    }
  }), [context]);
}

export function useConfigurationDraft(draft: ConfigurationDraft) {
  const context = useContext(Context);
  const id = useId();
  const current = useRef(draft);
  current.current = draft;
  useLayoutEffect(() => context?.register(id, () => ({
    ...current.current,
    id,
    namespace: context.namespace
  })), [context, id]);
  useLayoutEffect(() => {
    context?.changed();
  }, [context, draft.group, draft.label, draft.status]);
}

/** Covers unadapted child components conservatively; concrete registered drafts take precedence. */
export function useConfigurationFallback(unsafe: boolean, group: string, label: string) {
  useConfigurationDraft({group, label, status: unsafe ? 'blocked' : 'clean', fallback: true});
}
