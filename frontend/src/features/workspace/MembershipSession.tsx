import React, {createContext, useContext, useEffect, useRef, useState} from 'react';
import {Alert, Button, Flex, Typography} from 'antd';
import {z} from 'zod';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {AiApiError} from '@/services/arte-ai/request';
import {
  executeMembership,
  type MembershipAction,
  type MembershipCommand,
  membershipCommandSchema,
  type MembershipReceipt,
  queryMembershipReceipt
} from '@/services/platform/membership';
import {notifyAiConfiguration} from './aiConfigurationEvents';

const storedOperation = z.object({key: z.string().uuid(), command: membershipCommandSchema}).strict();
type Operation = z.infer<typeof storedOperation>;
type Confirmed = { command: MembershipCommand; receipt: MembershipReceipt };
const labels: Record<MembershipAction, string> = {
  issue: '发送邀请', revoke: '撤销邀请', accept: '接受邀请', decline: '拒绝邀请', role: '调整角色',
  remove: '移除成员', transfer: '交接负责人', leave: '退出空间'
};
const scopeLabel = ({target, targetLabel}: MembershipCommand) => targetLabel
  ?? (target.scope ? '工作空间' : '组织租户');

interface Session {
  locked: boolean;
  revision: number;
  status: React.ReactNode;

  run(command: MembershipCommand): Promise<boolean>;
}

const Context = createContext<Session | null>(null);
export const useMembershipSession = () => useContext(Context);

export function MembershipSession({userId, children}: { userId: string; children: React.ReactNode }) {
  const storage = `arte.ainew.membership.v1.${encodeURIComponent(JSON.stringify([userId]))}`;
  const [restored] = useState(() => {
    try {
      const value = sessionStorage.getItem(storage);
      return {operation: value ? storedOperation.parse(JSON.parse(value)) : null, blocked: false};
    } catch {
      return {operation: null, blocked: true};
    }
  });
  const original = useRef(restored.operation);
  const [pending, setPending] = useState(restored.operation);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [saving, setSaving] = useState(false);
  const [confirmed, setConfirmed] = useState<Confirmed>();
  const [revision, refresh] = useState(0);
  const [error, setError] = useState('');
  const [verification, setVerification] = useState('');
  const writing = useRef(false);
  const live = useRef(true);
  const reader = useRef<AbortController | null>(null);

  useConfigurationDraft({
    group: 'membership-command', label: '成员治理操作',
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

  function clear() {
    try {
      sessionStorage.removeItem(storage);
      original.current = null;
      setPending(null);
    } catch {
      setBlocked(true);
    }
  }

  async function run(command?: MembershipCommand) {
    if (!live.current || writing.current || blocked || command && original.current) return false;
    const replay = !!original.current;
    const operation = original.current ?? (command && {key: crypto.randomUUID(), command});
    if (!operation) return false;
    try {
      sessionStorage.setItem(storage, JSON.stringify(storedOperation.parse(operation)));
    } catch {
      setBlocked(true);
      return false;
    }
    original.current = operation;
    setPending(operation);
    writing.current = true;
    setSaving(true);
    setError('');
    setVerification('');
    reader.current?.abort();
    try {
      const receipt = await executeMembership(operation.command, operation.key);
      if (!live.current) return false;
      clear();
      setConfirmed({receipt, command: operation.command});
      refresh(value => value + 1);
      window.dispatchEvent(new CustomEvent('arte-membership-invitations-changed', {detail: userId}));
      const {target, action} = operation.command;
      if (action !== 'issue' && action !== 'revoke' && action !== 'decline') {
        if (target.scope) notifyAiConfiguration(userId, target.scope);
        else notifyAiConfiguration(userId, {tenantId: target.tenantId}, true);
      }
      return true;
    } catch (failure) {
      if (!live.current) return false;
      if (!replay && failure instanceof AiApiError && [400, 403, 404, 409].includes(failure.httpStatus)
        && !['PLATFORM_IDEMPOTENCY_CONFLICT', 'INVALID_MEMBERSHIP_RESPONSE'].includes(failure.code ?? '')) {
        clear();
        refresh(value => value + 1);
        setError(failure.code === 'PLATFORM_INVITATION_PENDING'
          ? '该账号已有有效邀请，请等待对方处理，或在已发邀请中撤销后重新邀请。'
          : failure.code === 'PLATFORM_ALREADY_MEMBER'
            ? '该账号已是成员；调整权限请使用成员目录中的角色管理。'
            : failure.code === 'PLATFORM_VERSION_CONFLICT'
              ? ['accept', 'decline'].includes(operation.command.action)
                ? '邀请已变化或失效。已请求最新邀请，请核对状态后重新操作。'
                : '记录已变化。已请求最新数据，请核对后重新操作。'
          : '操作未完成，请核对当前资格、目标状态和角色。');
      } else {
        setError('结果待确认。请保留原请求，通过原目标和原操作键确认。');
      }
      return false;
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  async function verify() {
    if (!confirmed || !live.current || writing.current) return;
    reader.current?.abort();
    const request = new AbortController();
    reader.current = request;
    setVerification('正在核验回执…');
    try {
      const value = await queryMembershipReceipt(confirmed.command, confirmed.receipt.operationId, {signal: request.signal});
      if (live.current && !request.signal.aborted) setVerification(`回执已核验 · 结果版本 ${value.resultVersion}`);
    } catch {
      if (live.current && !request.signal.aborted) setVerification('当前无法核验此回执，请核对操作资格和结果状态。');
    }
  }

  const status = (blocked || pending || confirmed || error) && <Flex vertical gap={8} style={{marginBottom: 8}}>
    {blocked && <Alert type="error" title="成员操作记录无法读写，请检查浏览器会话存储。"/>}
    {error && <Alert type="warning" title={error}/>}
    {pending && <Alert type="info" title={`${labels[pending.command.action]} · ${scopeLabel(pending.command)}`}
                       action={<Button disabled={blocked} loading={saving}
                                       onClick={() => void run()}>确认原成员操作</Button>}/>}
    {!pending && confirmed && <Flex gap={8} wrap align="center">
      <Typography.Text>{labels[confirmed.command.action]}已确认 · {scopeLabel(confirmed.command)}</Typography.Text>
      <Button size="small" onClick={() => void verify()}>核验成员操作回执</Button>
      {verification && <Typography.Text role="status">{verification}</Typography.Text>}
    </Flex>}
  </Flex>;
  return <Context.Provider value={{locked: saving || blocked || !!pending, revision, run, status}}>
    {children}
  </Context.Provider>;
}
