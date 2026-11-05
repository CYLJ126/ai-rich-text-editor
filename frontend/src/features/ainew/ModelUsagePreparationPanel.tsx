import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Descriptions, Flex, Tag, Typography} from 'antd';
import {
  getModelUsageChecks,
  getModelUsagePreparation,
  type ModelUsageChecks,
  type ModelUsagePreparation,
  type ModelUsageRequest,
  personalConditions,
  prepareModelUsage,
  spaceConditions
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
  WORKSPACE_AI_ACCESS_DENIED: '请联系执行空间负责人核对您的空间 AI 使用资格。',
  MODEL_USE_DENIED: '请联系模型授权管理员核对此空间内的模型使用资格。',
  WORKSPACE_POLICY_DENIED: '请联系执行空间负责人核对空间政策。',
  RESOURCE_PUBLICATION_REQUIRED: '请联系模型维护者发布可在空间启用的固定版本。',
  PREREQUISITE_UNVERIFIED: '请先处理上游条件或核对本人权限，再重新检查。',
  VERIFIED: '已通过当前检查。',
};

const conditionText = {
  PUBLICATION: ['发布版本与依赖', '请联系模型维护者核对发布及审核依赖。'],
  RUNTIME_ADAPTER: ['执行适配器', '请联系平台配置管理员核对协议和执行能力。'],
  EXECUTION_POLICY: ['执行空间政策', '请联系执行空间负责人核对政策。'],
  WORKSPACE_SELECTION: ['空间启用此版本', '请联系执行空间负责人启用此固定版本。'],
  SPACE_BUDGET_RULE: ['空间费率预算规则', '请联系执行空间负责人核对批准规则。'],
  WORKSPACE_AI_ACCESS: ['本人空间 AI 权限', '请联系执行空间负责人核对您的使用权限。'],
  MODEL_ACCESS: ['本人模型权限', '请联系模型授权管理员核对本人权限。'],
  CREDENTIAL_USE: ['本人使用凭据资格', '请联系密钥责任人核对使用授权。'],
  OWN_BUDGET: ['本人兼容预算', '请联系执行空间负责人核对预算规则与本人预算。'],
} as const;

const statusText = {PASS: '通过', BLOCKED: '待处理', UNKNOWN: '未核验', NOT_APPLICABLE: '不适用'};
const statusColor = {PASS: 'success', BLOCKED: 'warning', UNKNOWN: 'default', NOT_APPLICABLE: 'default'};

function UsageChecklist({checks}: { checks: ModelUsageChecks | null }) {
  const all = [...(checks?.spaceConditions ?? []), ...(checks?.personalConditions ?? [])];
  return <>
    {([['空间条件', spaceConditions], ['本人条件', personalConditions]] as const).map(([title, keys]) =>
      <Descriptions key={title} title={title} column={1} size="small" items={keys.map(key => {
        const result = all.find(item => item.condition === key);
        const status = result?.status ?? 'UNKNOWN';
        const reason = status === 'PASS' ? key === 'OWN_BUDGET' ? '兼容预算已具备，余额在发送时核验。' : ''
          : result ? usageReasonText[result.reason] ?? conditionText[key][1] : '';
        return {
          key, label: conditionText[key][0], children: <>
            <Tag color={statusColor[status]}>{statusText[status]}</Tag>{reason}
          </>
        };
      })}/>
    )}
  </>;
}

type Props = {
  context: BrowserContext;
  refreshRevision?: number;
  target: Omit<ModelUsageRequest, 'scope'> | null;
  disabled?: boolean;
  navigationDisabled?: boolean;
  onUnsafeChange: (unsafe: boolean) => void;
  onPrepared: () => void;
  onChecks?: (checks: ModelUsageChecks | null) => void;
  onUseInChat?: () => void;
  onConfigureWorkspace?: (model: Omit<ModelUsageRequest, 'scope'>) => void;
  onConfigureBudget?: (model: Omit<ModelUsageRequest, 'scope'>) => void;
};

/** 恢复只自动读取当前状态；未知写结果始终按原请求及原键确认，成功后的重读失败不再次写入。 */
export default function ModelUsagePreparationPanel({
                                                     context, refreshRevision = 0,
                                                     target,
                                                     disabled = false,
                                                     navigationDisabled = false,
                                                     onUnsafeChange,
                                                     onPrepared, onChecks,
                                                     onUseInChat, onConfigureWorkspace, onConfigureBudget
                                                   }: Props) {
  const [restored] = useState(() => readModelUsageOperation(context));
  const [operation, setOperation] = useState<ModelUsageOperation | null>(restored.operation);
  const operationRef = useRef(operation);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [diagnostic, setDiagnostic] = useState<{ key: string; value: ModelUsageChecks } | null>(null);
  const [preparation, setState] = useState<{ key: string; value: ModelUsagePreparation } | null>(null);
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
  const checks = diagnostic?.key === requestKey && !closed ? diagnostic.value : null;
  useEffect(() => onChecks?.(checks), [checks, onChecks]);
  const state = preparation?.key === requestKey ? preparation.value : null;
  const canPrepare = state?.state === 'PREPARABLE' && state.allowedActions.includes('PREPARE_OWN_BUDGET')
    && (!checks || checks.allowedActions.includes('PREPARE_OWN_BUDGET'));
  const canUseInChat = state?.state === 'READY' && (!checks ||
    [...checks.spaceConditions, ...checks.personalConditions].every(item => item.status === 'PASS'));
  const unsafe = !!operation || blocked || writing;
  useConfigurationDraft({
    group: 'models', label: '本人预算使用准备',
    status: blocked ? 'blocked' : writing ? 'submitting' : operation ? 'pending' : 'clean'
  });


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
    setDiagnostic(null);
    void getModelUsageChecks(request, {signal: controller.signal}).then(value => {
      if (live.current && !controller.signal.aborted && currentRequest.current === key) setDiagnostic({key, value});
    }).catch(() => {
      // 诊断失败时各项保持未核验；不影响原准备操作的确认和恢复。
    });
    setLoading(true);
    setError('');
    try {
      const result = await getModelUsagePreparation(request, {signal: controller.signal});
      if (!live.current || controller.signal.aborted || currentRequest.current !== key) return null;
      setState({key, value: result});
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
  }, [requestKey, refreshRevision]);

  async function prepare() {
    if (!request || !live.current || closed || writingRef.current || disabled
      || (!operationRef.current && (blocked || loading || !canPrepare))) return;
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
    if (disabled || navigationDisabled || unsafe || writingRef.current || closed || using.current || !canUseInChat) return;
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
      {request && <>
        <Typography.Text>执行空间：{request.scope.workspaceId}</Typography.Text>
        <details>
          <summary>检查目标版本</summary>
          {request.binding.id}@{request.binding.version}</details>
        <UsageChecklist checks={checks}/>
        {!checks && <Typography.Text
          type="secondary">未取得分项结果。请重新检查，或联系执行空间负责人核对读取资格。</Typography.Text>}
      </>}
      <Typography.Text
        type="secondary">按当前空间批准规则准备本人的兼容预算；已有账户和费用保留，准备不提高额度或自动应用聊天配置。</Typography.Text>
      {closed && <Alert type="warning" title="账号会话已结束，请重新登录。"/>}
      {blocked && <Alert type="warning" title="准备恢复记录不可用，暂不能创建新操作。请保留记录并恢复浏览器存储。"/>}
      {error && <Alert type="warning" showIcon title={error}/>}
      {notice && <Alert type="success" showIcon title={notice}/>}
      {state && (state.state !== 'READY' || canUseInChat) &&
        <Alert type={state.state === 'READY' ? 'success' : state.state === 'PREPARABLE' ? 'info' : 'warning'}
                       title={usageReasonText[state.reason] ?? '当前准备条件未满足，请重新核对权限、模型和预算规则。'}/>}
      {(checks ?? state)?.allowedActions.includes('CONFIGURE_WORKSPACE_AI') &&
        <Flex vertical gap={8}><Typography.Text>您可以维护此执行空间的 AI 配置；完成后重新检查。</Typography.Text>
          {onConfigureWorkspace && request && <Button disabled={disabled || navigationDisabled || unsafe || closed}
                                                      onClick={() => onConfigureWorkspace({
                                                        resourceId: request.resourceId,
                                                        binding: request.binding
                                                      })}>配置此空间 AI</Button>}</Flex>}
      {checks?.canConfigureBudget && onConfigureBudget && request &&
        <Button disabled={disabled || navigationDisabled || unsafe || closed}
                onClick={() => onConfigureBudget({resourceId: request.resourceId, binding: request.binding})}>
          查看此空间预算规则
        </Button>}
      {state?.approvedLimit &&
        <Typography.Text>规则批准额度：{state.approvedLimit.amount} {state.approvedLimit.currency}（非账户余额）</Typography.Text>}
      {operation &&
        <Typography.Text>原准备操作{operation.phase === 'confirmed' ? '已确认，等待重新读取当前状态' : '结果待确认，请使用原请求和原操作键重试'}。</Typography.Text>}
      <Flex wrap gap={8}>
        <Button loading={loading} disabled={closed || writing || !request}
                onClick={() => void refresh()}>重新读取当前准备状态</Button>
        <Button type={operation?.phase === 'pending' || canPrepare ? 'primary' : 'default'} loading={writing}
                disabled={closed || disabled || loading || !request || operation?.phase === 'confirmed'
                  || (!operation && (blocked || !canPrepare))}
                onClick={() => void prepare()}>
          {operation?.phase === 'pending' ? '重试确认原准备操作' : '准备此模型用于聊天'}
        </Button>
        {onUseInChat &&
          <Button disabled={closed || disabled || navigationDisabled || unsafe || loading || !canUseInChat}
                onClick={() => void returnToChat()}>
          返回聊天并选择此版本
          </Button>}
      </Flex>
    </Flex>
  </Card>;
}
