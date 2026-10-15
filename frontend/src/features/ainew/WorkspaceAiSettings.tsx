import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Divider, Flex, Form, Input, InputNumber, Modal, Select, Switch, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai';
import {
  type AiPreset,
  type CatalogView,
  getAiCatalog,
  getWorkspaceAi,
  importAiConfiguration,
  publishAiCatalog,
  refKey,
  updateWorkspaceAi,
  type WorkspaceAiView
} from '@/services/platform/ai';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {type BrowserContext, readOperation, saveOperation} from './storage';
import '../aiManagement.css';

export default function WorkspaceAiSettings({scope, context, onUpdated, onUnsafeChange, adminOnly = false}: {
  scope: WorkspaceScope;
  context: BrowserContext;
  onUpdated: () => void;
  onUnsafeChange?: (unsafe: boolean) => void;
  adminOnly?: boolean
}) {
  const [restored] = useState(() => readOperation<{
    kind: 'workspace' | 'import' | 'publish';
    request: unknown
  }>(context, 'ai-configuration', ''));
  const [unsafe, setUnsafe] = useState(!!restored.warning);
  useEffect(() => {
    onUnsafeChange?.(unsafe);
  }, [unsafe, onUnsafeChange]);
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
  const [view, setView] = useState<WorkspaceAiView>();
  const [preset, setPreset] = useState<AiPreset>();
  const [catalog, setCatalog] = useState<CatalogView>();
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [modelOpen, setModelOpen] = useState(false);
  const [modelForm] = Form.useForm();
  const pending = useRef<{
    kind: 'workspace' | 'import' | 'publish';
    key: string;
    request: unknown
  } | null>(restored.value ? {...restored.value.request, key: restored.value.key} : null);
  const [uncertain, setUncertain] = useState(!!restored.value);
  const [catalogEditable, setCatalogEditable] = useState(false);
  useEffect(() => {
    if (adminOnly) {
      void readCatalog();
      return;
    }
    const controller = new AbortController();
    getWorkspaceAi(scope, {signal: controller.signal}).then(({body}) => {
      if (controller.signal.aborted) return;
      setView(body.data);
      setPreset(body.data.configuration?.preset ?? body.data.defaults ?? undefined);
    }).catch(failure => {
      if (!controller.signal.aborted) setError(failure instanceof AiApiError && failure.httpStatus === 404
        ? '当前部署仍使用固定配置，空间资源管理尚未启用数据库模式。' : '空间 AI 设置暂时无法读取，请重新打开设置。');
    });
    return () => controller.abort();
  }, [scope]);
  const canEdit = !!view?.canManage && !saving && !uncertain && !unsafe;

  async function run(kind: 'workspace' | 'import' | 'publish', request: unknown) {
    if (pending.current && pending.current.kind !== kind) return;
    const operation = pending.current ?? {kind, key: crypto.randomUUID(), request};
    pending.current = operation;
    setUnsafe(!saveOperation(context, 'ai-configuration', '', {
      key: operation.key,
      request: {scope: context.scope, kind: operation.kind, request: operation.request}
    }));
    setSaving(true);
    setError('');
    try {
      if (kind === 'workspace') {
        const {body} = await updateWorkspaceAi(operation.request as Parameters<typeof updateWorkspaceAi>[0], operation.key);
        setView(body.data.value);
        setPreset(body.data.value.configuration!.preset);
        onUpdated();
      } else {
        const {body} = kind === 'import' ? await importAiConfiguration(operation.key)
          : await publishAiCatalog(operation.request as Parameters<typeof publishAiCatalog>[0], operation.key);
        setCatalog(body.data.value);
        setCatalogEditable(true);
        setModelOpen(false);
        onUpdated();
      }
      pending.current = null;
      setUncertain(false);
      saveOperation(context, 'ai-configuration', '', null);
      setUnsafe(false);
    } catch (failure) {
      const unknown = !(failure instanceof AiApiError) || failure.httpStatus >= 500 || (failure.httpStatus >= 200 && failure.httpStatus < 300);
      setUncertain(unknown);
      if (!unknown) {
        pending.current = null;
        setUnsafe(!saveOperation(context, 'ai-configuration', '', null));
      }
      setError(unknown ? '保存结果待确认，请保留本页并按原操作重试。'
        : failure instanceof AiApiError && failure.httpStatus === 403 ? '当前账号没有此项配置管理权限，请联系平台管理员。'
          : failure instanceof AiApiError && failure.httpStatus === 409 ? '配置已发生变化，请重新读取最新配置后修改。'
            : '配置未能保存，请检查模型、费率、预算额度和版本。');
    } finally {
      setSaving(false);
    }
  }

  async function readCatalog() {
    try {
      const {body} = await getAiCatalog();
      setCatalog(body.data);
      setCatalogEditable(true);
    } catch {
      setError('只有平台配置管理员可以维护已发布模型和新空间默认模板。');
    }
  }

  async function reload() {
    const {body} = await getWorkspaceAi(scope);
    setView(body.data);
    setPreset(body.data.configuration?.preset ?? body.data.defaults ?? undefined);
    setError('');
  }

  function choose(keys: string[]) {
    if (!preset || !view) return;
    const selected = view.models.filter(m => keys.includes(refKey(m.binding)));
    const rates = selected.map(m => m.rate).filter((r, i, all) => all.findIndex(x => refKey(x) === refKey(r)) === i);
    setPreset({
      ...preset,
      bindings: selected.map(m => m.binding),
      budgets: rates.map(rate => preset.budgets.find(b => refKey(b.rate) === refKey(rate))
        ?? view.defaults?.budgets.find(b => refKey(b.rate) === refKey(rate))!).filter(Boolean)
    });
  }

  return <Flex component="section" vertical gap={12} className="ai-management-panel" aria-label="空间 AI 配置">
    <Typography.Paragraph>在当前空间选择已发布模型并维护独立预算。新空间按已发布默认模板自动准备，已有空间不会被模板更新覆盖。</Typography.Paragraph>
    {unsafe && <Alert type="warning" title="配置操作无法保存到当前浏览器，请保留本页并确认原操作后再切换。"/>}
    {error && <Alert type="warning" showIcon title={error}/>}
    {uncertain && <Button loading={saving}
                          onClick={() => pending.current && void run(pending.current.kind, pending.current.request)}>重试确认原配置操作</Button>}
    {(view?.needsImport || (catalogEditable && catalog?.version === 0)) &&
      <Alert type="info" title="数据库尚未导入初始 AI 配置"
             description="平台管理员可一次性导入现有部署配置；密钥仍由环境或密钥服务提供。"
             action={<Button loading={saving} disabled={uncertain || unsafe}
                             onClick={() => void run('import', null)}>导入现有部署配置</Button>}/>}
    {view && !view.canManage && <Alert type="info" title="只有当前空间负责人可以修改空间 AI 设置。"/>}
    {preset && view?.configuration && <>
      <Form layout="vertical">
        <Form.Item label="启用此空间 AI"><Switch aria-label="启用此空间 AI" disabled={!canEdit} checked={preset.enabled}
                                                 onChange={enabled => setPreset({...preset, enabled})}/></Form.Item>
        <Form.Item label="空间可用模型"><Select aria-label="空间可用模型" mode="multiple" disabled={!canEdit}
                                                value={preset.bindings.map(refKey)}
                                                options={view.models.map(m => ({
                                                  label: `${m.name} · ${m.binding.version}`,
                                                  value: refKey(m.binding)
                                                }))} onChange={choose}/></Form.Item>
        {preset.budgets.map((budget, index) => <Form.Item key={refKey(budget.rate)}
                                                          label={`独立预算额度 · ${budget.limit.currency} · ${budget.rate.id}@${budget.rate.version}`}>
          <InputNumber aria-label={`预算额度 ${budget.rate.id}`} style={{width: '100%'}} stringMode min="0"
                       max="1000000000" disabled={!canEdit}
                       value={String(budget.limit.amount)} onChange={value => setPreset({
            ...preset,
            budgets: preset.budgets.map((b, i) => i === index ? {...b, limit: {...b.limit, amount: value ?? '0'}} : b)
          })}/>
        </Form.Item>)}
      </Form>
      <Typography.Paragraph>调整额度会保留已预留金额和已计费用；提高已批准额度需平台管理员权限。额度低于已占用金额时，会阻止新的调用。</Typography.Paragraph>
      <Flex gap={8} wrap>
      <Button type="primary" loading={saving} disabled={!canEdit} onClick={() => void run('workspace', {
        scope, expectedVersion: view.configuration!.version,
        expectedWorkspaceVersion: view.workspaceVersion, preset
      })}>保存空间 AI 配置</Button>
      <Button disabled={saving || uncertain || unsafe}
              onClick={() => void reload().catch(() => setError('最新配置读取失败，请重试。'))}>重新读取配置</Button>
      </Flex>
    </>}
    <Divider className="ai-management-divider"/>
    <Button disabled={saving || uncertain || unsafe}
            onClick={() => void readCatalog()}>维护已发布模型与默认模板</Button>
    {catalogEditable && catalog?.catalog && <>
      <Typography.Paragraph>发布版本：{catalog.version}。新增模型使用已审核连接、能力及费率，不修改历史版本。</Typography.Paragraph>
      <Button disabled={saving || uncertain || unsafe} onClick={() => setModelOpen(true)}>发布新模型</Button>
      <Form component="div" layout="vertical" className="ai-management-form">
      <Form.Item label="新空间自动启用 AI"><Switch aria-label="新空间自动启用 AI"
                                                   disabled={saving || uncertain || unsafe}
                                                   checked={catalog.catalog.defaults.enabled}
                                                   onChange={enabled => setCatalog({
                                                     ...catalog,
                                                     catalog: {
                                                       ...catalog.catalog!,
                                                       defaults: {...catalog.catalog!.defaults, enabled}
                                                     }
                                                   })}/></Form.Item>
      <Form.Item label="新空间默认模型"><Select aria-label="新空间默认模型" mode="multiple"
                                                disabled={saving || uncertain || unsafe}
                                                value={catalog.catalog.defaults.bindings.map(refKey)}
                                                options={catalog.catalog.bindings.map(b => ({
                                                  label: `${b.remoteOperation} · ${b.definition.version}`,
                                                  value: refKey(b.definition)
                                                }))}
                                                onChange={keys => {
                                                  const data = catalog.catalog!;
                                                  const selected = data.bindings.filter(b => keys.includes(refKey(b.definition)));
                                                  const rates = selected.map(b => b.rate).filter((rate, index, all) => all.findIndex(r => refKey(r) === refKey(rate)) === index);
                                                  setCatalog({
                                                    ...catalog, catalog: {
                                                      ...data, defaults: {
                                                        ...data.defaults, bindings: selected.map(b => b.definition),
                                                        budgets: rates.map(rate => data.defaults.budgets.find(b => refKey(b.rate) === refKey(rate)) ?? {
                                                          rate,
                                                          limit: {
                                                            amount: '0',
                                                            currency: data.rates.find(r => refKey(r.definition) === refKey(rate))!.inputPerMillion.currency
                                                          }
                                                        })
                                                      }
                                                    }
                                                  });
                                                }}/></Form.Item>
      {catalog.catalog.defaults.budgets.map((budget, index) => <Form.Item key={refKey(budget.rate)}
                                                                          label={`新空间默认额度 · ${budget.limit.currency} · ${budget.rate.id}`}>
        <InputNumber aria-label={`默认预算额度 ${budget.rate.id}`} stringMode min="0" max="1000000000"
                     disabled={saving || uncertain || unsafe}
                     value={String(budget.limit.amount)} onChange={value => setCatalog({
          ...catalog, catalog: {
            ...catalog.catalog!, defaults: {
              ...catalog.catalog!.defaults,
              budgets: catalog.catalog!.defaults.budgets.map((b, i) => index === i ? {
                ...b,
                limit: {...b.limit, amount: value ?? '0'}
              } : b)
            }
          }
        })}/>
      </Form.Item>)}
      </Form>
      <Button loading={saving} disabled={uncertain || unsafe} onClick={() => void run('publish', {
        expectedVersion: catalog.version,
        catalog: catalog.catalog
      })}>发布新空间默认模板</Button>
    </>}
    <Modal title="发布新模型" open={modelOpen} onCancel={() => !saving && setModelOpen(false)} confirmLoading={saving}
           okButtonProps={{disabled: uncertain}} onOk={() => void modelForm.validateFields().then(values => {
      if (!catalog?.catalog) return;
      const data = catalog.catalog;
      const capability = data.capabilities.find(c => refKey(c.definition) === values.capability)!;
      const connection = data.connections.find(c => refKey(c.definition) === values.connection)!.definition;
      const rate = data.rates.find(r => refKey(r.definition) === values.rate)!.definition;
      const binding = {
        definition: {type: 'binding', id: values.id.trim(), version: values.version.trim()}, capability, connection,
        rate, remoteOperation: values.remoteOperation.trim(), contextWindowTokens: values.contextWindowTokens
      };
      return run('publish', {
        expectedVersion: catalog.version,
        catalog: {...data, bindings: [...data.bindings, binding]}
      });
    }).catch(() => {
    })}>
      <Form form={modelForm} layout="vertical">
        <Form.Item name="id" label="模型标识" rules={[{required: true, whitespace: true}]}><Input
          maxLength={256}/></Form.Item>
        <Form.Item name="version" label="固定版本" rules={[{required: true, whitespace: true}]}><Input maxLength={256}/></Form.Item>
        <Form.Item name="remoteOperation" label="供应商模型名称" rules={[{required: true, whitespace: true}]}><Input
          maxLength={256}/></Form.Item>
        <Form.Item name="contextWindowTokens" label="上下文容量" rules={[{required: true}]}><InputNumber min={2}
                                                                                                         max={10000000}/></Form.Item>
        <Form.Item name="capability" label="已发布能力" rules={[{required: true}]}><Select
          options={catalog?.catalog?.capabilities.map(c => ({
            label: c.definition.id,
            value: refKey(c.definition)
          }))}/></Form.Item>
        <Form.Item name="connection" label="已审核连接" rules={[{required: true}]}><Select
          options={catalog?.catalog?.connections.map(c => ({
            label: `${c.providerId} · ${c.definition.id}`,
            value: refKey(c.definition)
          }))}/></Form.Item>
        <Form.Item name="rate" label="固定费率" rules={[{required: true}]}><Select
          options={catalog?.catalog?.rates.map(r => ({
            label: `${r.definition.id} · ${r.inputPerMillion.currency}`,
            value: refKey(r.definition)
          }))}/></Form.Item>
      </Form>
    </Modal>
  </Flex>;
}
