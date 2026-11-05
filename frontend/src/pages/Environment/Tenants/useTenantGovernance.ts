import {useEffect, useRef, useState} from 'react';
import {z} from 'zod';
import {AiApiError} from '@/services/arte-ai/request';
import {
  executeTenantCommand,
  getTenantCommandReceipt,
  type TenantCommand,
  tenantCommandSchema,
  type TenantReceipt
} from '@/services/platform/tenant';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';

const operationSchema = z.object({
  schemaVersion: z.literal(1),
  key: z.string().uuid(),
  command: tenantCommandSchema
}).strict();
type Operation = z.infer<typeof operationSchema>;
type Confirmed = { command: TenantCommand; receipt: TenantReceipt };

export function useTenantGovernance(userId: string, onApplied: (value: Confirmed) => void) {
  const storage = `arte.workspace.tenant-governance.v1.${encodeURIComponent(userId)}`;
  const [restored] = useState(() => {
    try {
      const value = sessionStorage.getItem(storage);
      return {pending: value ? operationSchema.parse(JSON.parse(value)) : null, blocked: false};
    } catch {
      return {pending: null, blocked: true};
    }
  });
  const original = useRef(restored.pending);
  const [pending, setPending] = useState(original.current);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [saving, setSaving] = useState(false);
  const [conflict, setConflict] = useState('');
  const [error, setError] = useState('');
  const [confirmed, setConfirmed] = useState<Confirmed>();
  const [verification, setVerification] = useState('');
  const [checking, setChecking] = useState(false);
  const writing = useRef(false);
  const live = useRef(true);
  const reader = useRef<AbortController | null>(null);
  const applied = useRef(onApplied);
  applied.current = onApplied;

  useConfigurationDraft({
    group: 'tenant-command', label: '租户操作',
    status: saving ? 'submitting' : blocked ? 'blocked' : pending ? 'pending' : 'clean'
  });

  useEffect(() => {
    live.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      live.current = false;
      reader.current?.abort();
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      reader.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId]);

  function clearPending() {
    try {
      sessionStorage.removeItem(storage);
      original.current = null;
      setPending(null);
      return true;
    } catch {
      setBlocked(true);
      return false;
    }
  }

  async function run(command?: TenantCommand) {
    if (!live.current || writing.current || blocked || command && original.current) return false;
    const replay = !!original.current;
    const operation = original.current ?? (command && {schemaVersion: 1 as const, key: crypto.randomUUID(), command});
    if (!operation) return false;
    try {
      sessionStorage.setItem(storage, JSON.stringify(operationSchema.parse(operation)));
    } catch {
      setBlocked(true);
      return false;
    }
    original.current = operation;
    setPending(operation);
    setError('');
    setConflict('');
    setSaving(true);
    writing.current = true;
    reader.current?.abort();
    setChecking(false);
    setVerification('');
    try {
      const receipt = await executeTenantCommand(operation.command, operation.key);
      if (!live.current) return false;
      const cleared = clearPending();
      const value = {command: operation.command, receipt};
      setConfirmed(value);
      applied.current(value);
      return cleared;
    } catch (failure) {
      if (!live.current) return false;
      if (failure instanceof AiApiError) {
        if (failure.code === 'PLATFORM_VERSION_CONFLICT') {
          if (operation.command.kind === 'update') setConflict(operation.command.request.tenantId);
          clearPending();
          setError(operation.command.kind === 'update' ? '租户版本已变化。请读取最新版本，原名称草稿仍保留。'
            : '组织未创建，请保留名称后重试。');
          return false;
        }
        if (!replay && [400, 403, 404, 409].includes(failure.httpStatus) && failure.code !== 'PLATFORM_IDEMPOTENCY_CONFLICT') {
          clearPending();
          setError('当前请求未完成，请核对名称、操作资格和组织创建配置。');
          return false;
        }
      }
      setError(failure instanceof AiApiError && [401, 403, 404].includes(failure.httpStatus)
        ? '当前无法核验原操作的资格。请保留记录，恢复资格后按原操作确认。'
        : '结果待确认。请使用原目标、原名称和原操作键确认。');
      return false;
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  async function query() {
    if (!confirmed || !live.current || writing.current) return;
    reader.current?.abort();
    const request = new AbortController();
    reader.current = request;
    setChecking(true);
    setVerification('');
    try {
      const result = await getTenantCommandReceipt(confirmed.receipt.operationId, confirmed.command, {signal: request.signal});
      if (live.current && !request.signal.aborted) {
        setVerification(`已核验原操作结果：${result.value.name} · 租户版本 ${result.resultVersion}`);
      }
    } catch {
      if (live.current && !request.signal.aborted) setVerification('暂时无法查询此回执，请核对当前操作资格后重试。');
    } finally {
      if (live.current && !request.signal.aborted) setChecking(false);
    }
  }

  return {
    pending, blocked, saving, error, conflict, clearConflict: () => setConflict(''),
    confirmed, checking, verification, query, run
  };
}

export type TenantGovernance = ReturnType<typeof useTenantGovernance>;
