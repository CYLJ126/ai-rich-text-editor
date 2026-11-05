import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Flex, Form, InputNumber, Select, Switch, Typography} from 'antd';
import {
  type AiModel,
  type BudgetPreset,
  type BudgetRateOption,
  getWorkspaceAi,
  getWorkspaceBudgetRules,
  listWorkspaceBudgetRateOptions,
  type ModelSelection,
  prepareWorkspaceAi,
  refKey,
  updateWorkspaceAi,
  updateWorkspaceBudgetRules,
  type WorkspaceAiView,
  type WorkspaceBudgetRulesView
} from '@/services/platform/ai';
import {AiApiError} from '@/services/arte-ai';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {type BrowserContext, readOperation, saveOperation} from '@/features/ainew/storage';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {confirmedWorkspaceVersion, type WorkspaceVersionChange} from './workspaceVersionChange';

type Operation = { kind: 'prepare' | 'models' | 'rules'; request: unknown; confirmed?: boolean };

/** 空间治理命令分别保存原请求和原键；不将模型选择联动为预算修改或账户准备。 */
export default function WorkspaceAiGovernanceEditor({
                                                      scope,
                                                      context,
                                                      onUpdated,
                                                      onUnsafeChange,
                                                      refreshRevision = 0,
                                                      versionChange,
                                                      focusedModel,
                                                      disabled = false
                                                    }: {
  disabled?: boolean;
  refreshRevision?: number;
  versionChange?: WorkspaceVersionChange;
  focusedModel?: AiModel;
  scope: WorkspaceScope;
  context: BrowserContext;
  onUpdated: (change?: WorkspaceVersionChange) => void;
  onUnsafeChange?: (unsafe: boolean) => void
}) {
  const [restored] = useState(() => readOperation<Operation>(context, 'workspace-ai-governance', ''));
  const pending = useRef(restored.value ? {key: restored.value.key, ...restored.value.request} : null);
  const [blocked, setBlocked] = useState(!!restored.warning);
  const [uncertain, setUncertain] = useState(!!restored.value);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [view, setView] = useState<WorkspaceAiView>();
  const [ruleView, setRuleView] = useState<WorkspaceBudgetRulesView>();
  const [rates, setRates] = useState<BudgetRateOption[]>([]);
  const [models, setModels] = useState<ModelSelection[]>([]);
  const [enabled, setEnabled] = useState(false);
  const [rules, setRules] = useState<BudgetPreset[]>([]);
  const [dirtyKind, setDirtyKind] = useState<'models' | 'rules' | null>(null);
  const dirty = dirtyKind !== null;
  const live = useRef(true);
  const writing = useRef(false);
  const reader = useRef<AbortController | null>(null);
  const [loading, setLoading] = useState(false);
  const unsafe = blocked || uncertain || saving || dirty;
  useConfigurationDraft({
    group: 'workspace-ai', label: dirtyKind === 'rules' ? '空间预算规则' : '空间 AI 配置', order: 20,
    status: blocked ? 'blocked' : saving ? 'submitting' : uncertain ? 'pending' : dirty ? 'dirty' : 'clean',
    save: dirtyKind === 'models' ? async () => {
      if (!view?.configuration || !view.canManage || disabled) return false;
      return run('models', {
        scope, expectedWorkspaceVersion: view.workspaceVersion,
        expectedVersion: view.configuration.version, enabled, models
      });
    } : undefined,
    discard: async () => {
      try {
        await load();
        return true;
      } catch {
        setError('配置读取失败，当前修改已保留。');
        return false;
      }
    }
  });
  useEffect(() => {
    onUnsafeChange?.(unsafe);
  }, [unsafe, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (unsafe) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unsafe]);

  useEffect(() => {
    if (!versionChange) return;
    setView(value => value?.workspaceVersion === versionChange.from ? {
      ...value,
      workspaceVersion: versionChange.to
    } : value);
    setRuleView(value => value?.workspaceVersion === versionChange.from ? {
      ...value,
      workspaceVersion: versionChange.to
    } : value);
  }, [versionChange, view?.workspaceVersion, ruleView?.workspaceVersion]);

  async function load(outerSignal?: AbortSignal, preserve = false) {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    const signal = controller.signal;
    const cancel = () => controller.abort();
    if (outerSignal?.aborted) return;
    outerSignal?.addEventListener('abort', cancel, {once: true});
    setLoading(true);
    try {
      const result = (await getWorkspaceAi(scope, {signal})).body.data;
      if (!live.current || signal?.aborted) return;
      if (result.configuration && (result.configuration.scope.tenantId !== scope.tenantId || result.configuration.scope.workspaceId !== scope.workspaceId)) {
        throw new AiApiError(200, {code: 'INVALID_WORKSPACE_AI_RESPONSE'});
      }
      let budget: WorkspaceBudgetRulesView | undefined;
      const options: BudgetRateOption[] = [];
      if (result.canManage) {
        budget = (await getWorkspaceBudgetRules(scope, {signal})).body.data;
        if (budget.scope.tenantId !== scope.tenantId || budget.scope.workspaceId !== scope.workspaceId) {
          throw new AiApiError(200, {code: 'INVALID_WORKSPACE_RULE_RESPONSE'});
        }
        let cursor: string | undefined;
        const seen = new Set<string>();
        do {
          const page = (await listWorkspaceBudgetRateOptions(scope, cursor, {signal})).body.data;
          options.push(...page.items);
          cursor = page.nextCursor ?? undefined;
          if (cursor && seen.has(cursor)) throw new AiApiError(200, {code: 'INVALID_WORKSPACE_RATE_CURSOR'});
          if (cursor) seen.add(cursor);
        } while (cursor);
      }
      if (!live.current || signal?.aborted) return;
      setView(result);
      setRuleView(budget);
      setRates(options);
      if (!preserve || dirtyKind !== 'models') {
        setEnabled(result.configuration?.enabled ?? false);
        setModels(result.configuration?.models ?? []);
      }
      if (!preserve || dirtyKind !== 'rules') setRules(budget?.configuration?.rules ?? []);
      if (!preserve) setDirtyKind(null);
    } finally {
      outerSignal?.removeEventListener('abort', cancel);
      if (live.current && !signal.aborted) setLoading(false);
    }
  }

  useEffect(() => {
    live.current = true;
    const controller = new AbortController();
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail === context.userId) {
        live.current = false;
        controller.abort();
        reader.current?.abort();
      }
    };
    window.addEventListener('arte-account-session-cleared', logout);
    void load(controller.signal).catch(() => {
      if (live.current && !controller.signal.aborted) setError('空间 AI 配置读取失败，请重新读取。');
    });
    return () => {
      live.current = false;
      controller.abort();
      reader.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [scope, context.userId]);

  async function run(kind: Operation['kind'], request: unknown) {
    if (!live.current || writing.current || blocked) return false;
    const operation = pending.current ?? {kind, request, key: crypto.randomUUID()};
    if (operation.kind !== kind) return false;
    pending.current = operation;
    const persist = () => saveOperation(context, 'workspace-ai-governance', '', {
      key: operation.key,
      request: {scope, kind: operation.kind, request: operation.request, confirmed: operation.confirmed ?? false}
    });
    if (!persist()) {
      setBlocked(true);
      setUncertain(true);
      return false;
    }
    writing.current = true;
    setSaving(true);
    setUncertain(true);
    setError('');
    try {
      if (!operation.confirmed) {
        const response = kind === 'prepare'
          ? await prepareWorkspaceAi(operation.request as Parameters<typeof prepareWorkspaceAi>[0], operation.key)
          : kind === 'models'
            ? await updateWorkspaceAi(operation.request as Parameters<typeof updateWorkspaceAi>[0], operation.key)
            : await updateWorkspaceBudgetRules(operation.request as Parameters<typeof updateWorkspaceBudgetRules>[0], operation.key);
        const receipt = response.body.data;
        const resultScope = 'scope' in receipt.value ? receipt.value.scope : receipt.value.configuration?.scope;
        if (receipt.status !== 1 || !resultScope || resultScope.tenantId !== scope.tenantId || resultScope.workspaceId !== scope.workspaceId) {
          throw new AiApiError(200, {code: 'INVALID_WORKSPACE_AI_RESPONSE'});
        }
        if (!live.current) return false;
        operation.confirmed = true;
        if (!persist()) {
          setBlocked(true);
          return false;
        }
      }
      await load();
      if (!live.current) return false;
      if (!saveOperation(context, 'workspace-ai-governance', '', null)) {
        setBlocked(true);
        return false;
      }
      pending.current = null;
      setUncertain(false);
      setBlocked(false);
      setSaved(operation.kind === 'prepare' ? '首次准备已保存；尚未选择模型或创建账户。' : operation.kind === 'models' ? '空间开关与模型版本已保存；预算规则保持独立。' : '独立预算规则已保存；模型选择保持独立。');
      const request = operation.request as { expectedWorkspaceVersion?: unknown } | undefined;
      onUpdated(confirmedWorkspaceVersion(request?.expectedWorkspaceVersion));
      return true;
    } catch (failure) {
      if (!live.current) return false;
      const unknown = operation.confirmed || !(failure instanceof AiApiError) || failure.httpStatus >= 500 || failure.httpStatus < 300;
      if (!unknown) {
        pending.current = null;
        setUncertain(false);
        setBlocked(!saveOperation(context, 'workspace-ai-governance', '', null));
      }
      setError(unknown ? '保存结果待确认，请保留本页并按原操作重试。'
        : failure instanceof AiApiError && failure.httpStatus === 409 ? '配置已变化。可读取最新版本并保留草稿，核对后再保存。' : '配置未保存，请核对当前权限、版本和批准额度。');
      return false;
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  const canEdit = !disabled && !!view?.canManage && !loading && !saving && !uncertain && !blocked;
  const canEditModels = canEdit && dirtyKind !== 'rules';
  const canEditRules = canEdit && dirtyKind !== 'models';
  const focusedKey = focusedModel ? refKey(focusedModel.binding) : '';
  const availableFocus = focusedModel && view?.models.some(item =>
    item.resourceId === focusedModel.resourceId && refKey(item.binding) === focusedKey);
  const savedFocus = focusedModel && view?.configuration?.models.some(item =>
    item.resourceId === focusedModel.resourceId && refKey(item.binding) === focusedKey);
  const draftFocus = focusedModel && models.some(item =>
    item.resourceId === focusedModel.resourceId && refKey(item.binding) === focusedKey);
  const focusedRule = focusedModel && ruleView?.configuration?.rules.find(rule => refKey(rule.rate) === refKey(focusedModel.rate));
  return <Flex component="section" vertical gap={12} aria-label="空间 AI 配置">
    <Typography.Paragraph>为此空间启用实际模型的固定版本，并独立维护费率预算规则。首次准备不选择模型，也不创建本人账户。</Typography.Paragraph>
    {focusedModel && <Flex vertical gap={8} aria-label="定位模型版本">
      <Typography.Text strong>{focusedModel.name} · 版本 {focusedModel.binding.version}</Typography.Text>
      {!view ? <Typography.Text type="secondary">等待读取执行空间配置。</Typography.Text>
        : !availableFocus ? <Alert type="warning" title="该精确版本已不在此空间的可配置目录中。"/>
          : <>
            <Typography.Text>{view.configuration
              ? `${view.configuration.enabled ? '空间 AI 已启用。' : '空间 AI 尚未启用。'}${savedFocus
                ? '此版本已配置在空间中。' : '此版本尚未配置在空间中。'}`
              : '待首次准备空间 AI 配置。'}</Typography.Text>
            <Typography.Text>{!ruleView ? '当前无法核验此费率的空间预算规则。'
              : focusedRule && Number(focusedRule.limit.amount) > 0 ? '此费率已有正额度规则。'
                : '此费率待配置正额度规则。'} 本人使用条件需在模型详情中重新检查。</Typography.Text>
            {canEditModels && view.configuration && !draftFocus && <Button onClick={() => {
              setModels(current => [...current.filter(item => item.resourceId !== focusedModel.resourceId),
                {resourceId: focusedModel.resourceId, binding: focusedModel.binding}]);
              setDirtyKind('models');
            }}>加入模型草稿</Button>}
          </>}
    </Flex>}
    {dirty && <Typography.Text>请先保存当前修改，或重新读取放弃修改后，再编辑另一项。</Typography.Text>}
    {saved && <Alert type="success" title={saved}/>}
    {refreshRevision > 0 && <Typography.Text>空间配置有更新，请按需重新读取；当前草稿保持不变。</Typography.Text>}
    {error && <Alert type="warning" title={error}/>}
    {blocked && <Alert type="warning" title="原操作记录暂不可用，请恢复浏览器存储后确认。"/>}
    {uncertain && <Button loading={saving} disabled={blocked}
                          onClick={() => pending.current && void run(pending.current.kind, pending.current.request)}>重试确认原配置操作</Button>}
    {view && !view.canManage && <Alert type="info" title="只有当前空间负责人可以修改空间 AI 设置。"/>}
    {view?.needsImport && <Alert type="info" title="平台尚未初始化审核目录，请联系平台配置管理员。"/>}
    {view && !view.configuration && !view.needsImport &&
      <Button type="primary" disabled={!canEdit} loading={saving} onClick={() => void run('prepare', {
        scope, expectedWorkspaceVersion: view.workspaceVersion, expectedTemplateVersion: view.templateVersion
      })}>首次准备空间 AI 配置</Button>}
    {view?.configuration && <>
      <Form layout="vertical">
        <Form.Item label="启用此空间 AI"><Switch aria-label="启用此空间 AI" disabled={!canEditModels} checked={enabled}
                                                 onChange={value => {
                                                   setEnabled(value);
                                                   setDirtyKind('models');
                                                 }}/></Form.Item>
        <Form.Item label="空间可用模型"><Select aria-label="空间可用模型" mode="multiple" disabled={!canEditModels}
                                                value={models.map(model => refKey(model.binding))}
                                                options={view.models.map(model => ({
                                                  value: refKey(model.binding),
                                                  label: `${model.name} · ${model.kind === 'PERSONAL' ? '个人' : '租户'} · ${model.binding.version}`
                                                }))}
                                                onChange={keys => {
                                                  const selected = new Map<string, ModelSelection>();
                                                  for (const key of keys) {
                                                    const model = view.models.find(value => refKey(value.binding) === key);
                                                    if (model) selected.set(model.resourceId, {
                                                      resourceId: model.resourceId,
                                                      binding: model.binding
                                                    });
                                                  }
                                                  setModels([...selected.values()]);
                                                  setDirtyKind('models');
                                                }}/></Form.Item>
      </Form>
      <Button type="primary" disabled={!canEditModels} loading={saving} onClick={() => {
        if (view.configuration) void run('models', {
          scope,
          expectedWorkspaceVersion: view.workspaceVersion,
          expectedVersion: view.configuration.version,
          enabled,
          models
        });
      }}>保存空间 AI 配置</Button>
      {ruleView?.configuration && <>
        <Form layout="vertical">
          <Form.Item label="独立预算费率"><Select aria-label="独立预算费率" mode="multiple" disabled={!canEditRules}
                                                  value={rules.map(rule => refKey(rule.rate))}
                                                  options={rates.map(option => ({
                                                    value: refKey(option.rate),
                                                    label: `${option.kind === 'MODEL' ? '模型' : '工具'} · ${option.rate.id}@${option.rate.version} · ${option.currency}`
                                                  }))}
                                                  onChange={keys => {
                                                    setRules(rates.filter(option => keys.includes(refKey(option.rate))).map(option => rules.find(rule => refKey(rule.rate) === refKey(option.rate))
                                                      ?? {
                                                        rate: option.rate,
                                                        limit: {amount: '0', currency: option.currency}
                                                      }));
                                                    setDirtyKind('rules');
                                                  }}/></Form.Item>
          {rules.map((rule, index) => <Form.Item key={refKey(rule.rate)}
                                                 label={`独立预算额度 · ${rule.rate.id}@${rule.rate.version} · ${rule.limit.currency}`}>
            <InputNumber aria-label={`预算额度 ${rule.rate.id}`} stringMode min="0" max="1000000000"
                         disabled={!canEditRules} value={String(rule.limit.amount)}
                         onChange={amount => {
                           setRules(rules.map((value, i) => i === index ? {
                             ...value,
                             limit: {...value.limit, amount: amount ?? '0'}
                           } : value));
                           setDirtyKind('rules');
                         }}/>
          </Form.Item>)}
        </Form>
        <Typography.Paragraph>移除模型保留预算规则。撤销规则保留历史账户和费用，并阻止新调用。修改额度保留预留及已计费用；超出已批准上限需平台提额资格。</Typography.Paragraph>
        <Button disabled={!canEditRules} loading={saving} onClick={() => {
          if (ruleView.configuration) void run('rules', {
            scope,
            expectedWorkspaceVersion: ruleView.workspaceVersion,
            expectedRuleVersion: ruleView.configuration.version,
            rules
          });
        }}>保存独立预算规则</Button>
      </>}
    </>}
    <Flex gap={8} wrap>
      {dirty && <Button disabled={loading || saving || uncertain || blocked}
                        onClick={() => void load(undefined, true).then(() => {
                          if (live.current) setError('已读取最新版本，当前草稿已保留。保存前请核对完整模型或预算规则。');
                        }).catch(() => setError('最新配置读取失败，当前草稿已保留。'))}>读取最新版本并保留 AI
        草稿</Button>}
      <Button disabled={loading || saving || uncertain || blocked}
              onClick={() => void load().catch(() => setError('最新配置读取失败，请重试。'))}>
        {dirty ? '放弃 AI 草稿并重新读取' : '重新读取配置'}
      </Button>
    </Flex>
  </Flex>;
}
