import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Typography} from 'antd';
import {
  getModelUsagePreparation,
  type ModelUsagePreparation,
  type ModelUsageRequest,
  prepareModelUsage
} from '@/services/platform/modelUsage';
import type {BrowserContext} from './storage';
import {modelFailure, modelStatusText, uncertainModelFailure} from './modelManagementState';
import {
  type ModelUsageOperation,
  readModelUsageOperation,
  saveModelChatHandoff,
  saveModelUsageOperation
} from './modelUsageRecovery';

export const usageReasonText: Record<string, string> = {
  ...modelStatusText,
  READY: '当前使用条件和兼容预算已具备。返回聊天后仍须重新发现并明确应用；发送时继续核验权限和余额。',
  BUDGET_NOT_PREPARED: '已有批准的额度规则，可以为本人准备所选精确费率的预算。',
  BUDGET_RULE_NOT_CONFIGURED: '当前空间尚未批准此精确费率的额度。请联系配置管理员维护空间 AI 预算规则，再重新检查。',
  BUDGET_RULE_ZERO: '批准额度为零，暂不能初始化预算。请联系配置管理员核对额度。',
  WORKSPACE_AI_NOT_PREPARED: '当前空间 AI 尚未准备，请由空间负责人显式完成首次准备。',
  WORKSPACE_AI_DISABLED: '当前空间 AI 已关闭，请联系空间负责人检查此空间的配置。',
  CATALOG_NOT_INITIALIZED: '发布目录尚未初始化，请联系平台配置管理员。',
  MODEL_NOT_MIGRATED: '此旧目录模型尚未显式切入公共授权，请联系配置管理员核对迁移。',
  MODEL_NOT_ENABLED: '此模型尚未在当前空间启用，请联系空间负责人配置。',
  MODEL_VERSION_NOT_ENABLED: '当前空间启用的是此模型的其他固定版本，请检查所选版本。',
  CONNECTION_DISABLED: '审核连接已停用，请联系平台配置管理员。',
};

type Props = {
  context: BrowserContext;
  target: Omit<ModelUsageRequest, 'scope'> | null;
  disabled?: boolean;
  navigationDisabled?: boolean;
  onUnsafeChange: (unsafe: boolean) => void;
  onPrepared: () => void;
  onUseInChat?: () => void;
};

/** 恢复只自动读取当前状态；未知写结果始终按原请求及原键确认，成功后的重读失败不再次写入。 */
export default function ModelUsagePreparationPanel({
                                                     context,
                                                     target,
                                                     disabled = false,
                                                     navigationDisabled = false,
                                                     onUnsafeChange,
                                                     onPrepared,
                                                     onUseInChat
                                                   }: Props) {
  const [restored] = useState(() => readModelUsageOperation(context));
  const [operation, setOperation] = useState<ModelUsageOperation | null>(restored.operation);
  const operationRef = useRef(operation);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [state, setState] = useState<ModelUsagePreparation | null>(null);
  const [loading, setLoading] = useState(false);
  const [writing, setWriting] = useState(false);
  const writingRef = useRef(false);
  const [closed, setClosed] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const live = useRef(true);
  const read = useRef<AbortController | null>(null);
  const callbacks = useRef({onPrepared, onUseInChat});
  callbacks.current = {onPrepared, onUseInChat};
  const request = operation?.request ?? (target ? {scope: context.scope, ...target} : restored.operation?.request ?? null);
  const flags = useRef({disabled, navigationDisabled});
  flags.current = {disabled, navigationDisabled};
  const using = useRef(false);
  const requestKey = JSON.stringify(request);
  const currentRequest = useRef(requestKey);
  currentRequest.current = requestKey;
  const unsafe = !!operation || blocked || writing;

  useEffect(() => {
    onUnsafeChange(unsafe);
  }, [unsafe, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange(false), [onUnsafeChange]);
  useEffect(() => {
    live.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== context.userId) return;
      live.current = false;
      read.current?.abort();
      setState(null);
      setClosed(true);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      read.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context.userId]);

  async function refresh() {
    if (!request || !live.current) return null;
    read.current?.abort();
    const controller = new AbortController();
    read.current = controller;
    const key = requestKey;
    setState(null);
    setLoading(true);
    setError('');
    try {
      const result = await getModelUsagePreparation(request, {signal: controller.signal});
      if (!live.current || controller.signal.aborted || currentRequest.current !== key) return null;
      setState(result);
      const confirmed = operationRef.current;
      if (confirmed?.phase === 'confirmed') {
        if (!saveModelUsageOperation(context, null)) {
          setBlocked(true);
          setError('准备已确认，但恢复记录无法清除。恢复浏览器存储后只需重新读取，不要创建新操作。');
          return null;
        }
        operationRef.current = null;
        setOperation(null);
        setBlocked(false);
        setNotice('准备操作已确认，当前状态已重新读取；预算批准额度不是账户余额。');
        callbacks.current.onPrepared();
      }
      return result;
    } catch (failure) {
      if (live.current && !controller.signal.aborted && currentRequest.current === key) {
        setError(operationRef.current?.phase === 'confirmed'
          ? '准备已确认，但当前状态读取失败。请重新读取当前准备状态，无需再次提交。'
          : modelFailure(failure));
      }
      return null;
    } finally {
      if (live.current && !controller.signal.aborted && currentRequest.current === key) setLoading(false);
    }
  }

  useEffect(() => {
    setState(null);
    setNotice('');
    void refresh();
    return () => read.current?.abort();
  }, [requestKey]);

  async function prepare() {
    if (!request || !live.current || closed || writingRef.current || disabled) return;
    if (operationRef.current?.phase === 'confirmed') {
      await refresh();
      return;
    }
    const pending = operationRef.current ?? {key: crypto.randomUUID(), request, phase: 'pending' as const};
    operationRef.current = pending;
    setOperation(pending);
    setError('');
    setNotice('');
    if (!saveModelUsageOperation(context, pending)) {
      setBlocked(true);
      setError('无法保存原请求和操作键，本次未提交。恢复浏览器存储后重试原操作。');
      return;
    }
    setBlocked(false);
    writingRef.current = true;
    setWriting(true);
    try {
      await prepareModelUsage(pending.request, pending.key);
      if (!live.current) return;
      // 回执说明原操作已提交；可用性必须再读，不能直接将历史 READY 当成当前许可。
      const confirmed: ModelUsageOperation = {...pending, phase: 'confirmed'};
      operationRef.current = confirmed;
      setOperation(confirmed);
      if (!saveModelUsageOperation(context, confirmed)) {
        setBlocked(true);
        setError('准备已确认，但本地确认状态未保存。请恢复存储后重新读取；本页不会再次写入。');
        return;
      }
      await refresh();
    } catch (failure) {
      if (!live.current) return;
      setState(null);
      setError(modelFailure(failure));
      if (!uncertainModelFailure(failure)) {
        if (saveModelUsageOperation(context, null)) {
          operationRef.current = null;
          setOperation(null);
        } else setBlocked(true);
      }
    } finally {
      writingRef.current = false;
      if (live.current) setWriting(false);
    }
  }

  async function returnToChat() {
    if (disabled || navigationDisabled || unsafe || writingRef.current || closed || using.current) return;
    using.current = true;
    try {
      const current = await refresh();
      if (!request || !current || current.state !== 'READY' || operationRef.current || !live.current
        || currentRequest.current !== requestKey || flags.current.disabled || flags.current.navigationDisabled) return;
      if (!saveModelChatHandoff(context, request)) {
        setError('无法保存待选择模型，尚未返回聊天。请检查浏览器存储后重试。');
        return;
      }
      setNotice('已记录待选择的精确版本，请在聊天中重新发现并确认应用。');
      callbacks.current.onUseInChat?.();
    } finally {
      using.current = false;
    }
  }

  if (!request && !blocked) return null;
  return <Card size="small" title="聊天使用准备" aria-label="聊天使用准备">
    <Flex vertical gap={8}>
      {request &&
        <Typography.Text>执行空间：{request.scope.workspaceId} · {request.binding.id}@{request.binding.version}</Typography.Text>}
      <Typography.Text
        type="secondary">按当前空间批准规则准备本人的兼容预算；已有账户和费用保留，准备不提高额度或自动应用聊天配置。</Typography.Text>
      {closed && <Alert type="warning" title="账号会话已结束，请重新登录。"/>}
      {blocked && <Alert type="warning" title="准备恢复记录不可用，暂不能创建新操作。请保留记录并恢复浏览器存储。"/>}
      {error && <Alert type="warning" showIcon title={error}/>}
      {notice && <Alert type="success" showIcon title={notice}/>}
      {state && <Alert type={state.state === 'READY' ? 'success' : state.state === 'PREPARABLE' ? 'info' : 'warning'}
                       title={usageReasonText[state.reason] ?? '当前准备条件未满足，请重新核对权限、模型和预算规则。'}/>}
      {state?.allowedActions.includes('CONFIGURE_WORKSPACE_AI') &&
        <Typography.Text>您可以维护此执行空间的 AI 配置；完成后重新检查。</Typography.Text>}
      {state?.approvedLimit &&
        <Typography.Text>规则批准额度：{state.approvedLimit.amount} {state.approvedLimit.currency}（非账户余额）</Typography.Text>}
      {operation &&
        <Typography.Text>原准备操作{operation.phase === 'confirmed' ? '已确认，等待重新读取当前状态' : '结果待确认，请使用原请求和原操作键重试'}。</Typography.Text>}
      <Flex wrap gap={8}>
        <Button loading={loading} disabled={closed || writing || !request}
                onClick={() => void refresh()}>重新读取当前准备状态</Button>
        <Button type="primary" loading={writing}
                disabled={closed || disabled || loading || !request || operation?.phase === 'confirmed'
                  || (!operation && (blocked || state?.state !== 'PREPARABLE' || !state.allowedActions.includes('PREPARE_OWN_BUDGET')))}
                onClick={() => void prepare()}>
          {operation?.phase === 'pending' ? '重试确认原准备操作' : '准备此模型用于聊天'}
        </Button>
        <Button disabled={closed || disabled || navigationDisabled || unsafe || loading || state?.state !== 'READY'}
                onClick={() => void returnToChat()}>
          返回聊天并选择此版本
        </Button>
      </Flex>
    </Flex>
  </Card>;
}
