import React, {createContext, useContext, useEffect, useRef, useState} from 'react';
import {Alert, Button, Flex} from 'antd';
import {z} from 'zod';
import {AiApiError} from '@/services/arte-ai/request';
import {
  executeLifecycle,
  type LifecycleCommand,
  lifecycleCommandSchema,
  type LifecycleReceipt
} from '@/services/platform/workspaceLifecycle';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {notifyAiConfiguration} from './aiConfigurationEvents';

const savedSchema = z.object({key: z.string().uuid(), command: lifecycleCommandSchema}).strict();
const Context = createContext<{
  locked: boolean;
  revision: number;
  run(command?: LifecycleCommand): Promise<LifecycleReceipt | undefined>;
  status: React.ReactNode
} | null>(null);
export const useLifecycleSession = () => useContext(Context);

export function LifecycleSession({userId, children}: { userId: string; children: React.ReactNode }) {
  const storage = `arte.workspace.lifecycle.v1.${encodeURIComponent(userId)}`;
  const [restored] = useState(() => {
    try {
      const value = sessionStorage.getItem(storage);
      return {operation: value ? savedSchema.parse(JSON.parse(value)) : undefined, blocked: false};
    } catch {
      return {operation: undefined, blocked: true};
    }
  });
  const original = useRef(restored.operation);
  const [pending, setPending] = useState(restored.operation);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [saving, setSaving] = useState(false);
  const [revision, setRevision] = useState(0);
  const [message, setMessage] = useState('');
  const live = useRef(true), writing = useRef(false);
  useConfigurationDraft({
    group: 'workspace-lifecycle-command',
    label: '空间生命周期操作',
    status: blocked ? 'blocked' : saving ? 'submitting' : pending ? 'pending' : 'clean'
  });
  useEffect(() => {
    live.current = true;
    const cleared = (event: Event) => {
      if ((event as CustomEvent<string>).detail === userId) live.current = false;
    };
    window.addEventListener('arte-account-session-cleared', cleared);
    return () => {
      live.current = false;
      window.removeEventListener('arte-account-session-cleared', cleared);
    };
  }, [userId]);

  function clear() {
    try {
      sessionStorage.removeItem(storage);
      original.current = undefined;
      setPending(undefined);
    } catch {
      setBlocked(true);
    }
  }

  async function run(command?: LifecycleCommand): Promise<LifecycleReceipt | undefined> {
    if (!live.current || blocked || writing.current || command && original.current) return undefined;
    const replay = !!original.current;
    const operation = original.current ?? (command ? {key: crypto.randomUUID(), command} : undefined);
    if (!operation) return undefined;
    try {
      sessionStorage.setItem(storage, JSON.stringify(savedSchema.parse(operation)));
    } catch {
      setBlocked(true);
      return undefined;
    }
    original.current = operation;
    setPending(operation);
    writing.current = true;
    setSaving(true);
    setMessage('');
    try {
      const receipt = await executeLifecycle(operation.command, operation.key);
      if (!live.current) return undefined;
      clear();
      setRevision(value => value + 1);
      notifyAiConfiguration(userId, operation.command.scope);
      setMessage(operation.command.action === 'close' ? `「${operation.command.name}」关闭已受理，可在关闭记录查看进度；受理不代表已删除。` : '空间状态已更新。');
      return receipt;
    } catch (failure) {
      if (!live.current) return undefined;
      if (!replay && failure instanceof AiApiError && [400, 403, 404, 409].includes(failure.httpStatus)
        && !['INVALID_LIFECYCLE_RESPONSE', 'PLATFORM_IDEMPOTENCY_CONFLICT'].includes(failure.code ?? '')) {
        clear();
        setRevision(value => value + 1);
        setMessage('操作未完成，当前权限、版本或依赖已变化，请重新读取后核对。');
      } else setMessage('结果待确认，请用原操作键确认，不要重新创建相同操作。');
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
    return undefined;
  }

  const status = <Flex vertical gap={8}>
    {blocked && <Alert type="error" title="生命周期操作记录无法可靠读写，请检查浏览器会话存储。"/>}
    {message && <Alert type="info" title={message}/>}
    {pending && <Alert type="warning" title={`「${pending.command.name}」有待确认生命周期操作`}
                       action={<Button aria-label="确认原生命周期操作" disabled={blocked} loading={saving}
                                       onClick={() => void run()}>确认原生命周期操作</Button>}/>}
  </Flex>;
  return <Context.Provider
    value={{locked: blocked || saving || !!pending, revision, run, status}}>{children}</Context.Provider>;
}
