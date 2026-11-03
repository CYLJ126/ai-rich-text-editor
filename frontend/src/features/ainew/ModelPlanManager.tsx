import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Form, Input, InputNumber, Popconfirm, Select, Switch, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai/request';
import {type ConnectionView, getConnections} from '@/services/platform/connection';
import {
  getModelPlans,
  type ManagedModelPlan,
  type ModelPlanCommand,
  type ModelPlanSettings,
  modelPlanSettingsSchema,
  type ModelPlanView,
  publishModelPlan
} from '@/services/platform/modelPlan';
import {refKey} from '@/services/platform/ai';
import {type Operation, readOperation, saveOperation} from './storage';

type Fields = Omit<ModelPlanSettings, 'connection'> & { connectionKey: string };
type Saved = { scope: { tenantId: string; workspaceId: string }; command: ModelPlanCommand };
type Props = { userId: string; onUnsafeChange?: (value: boolean) => void };
const defaults = {currency: 'CNY', contextWindowTokens: 8192, usageReporting: false, toolCalls: false};

/** 平台登记价格不代替个人模型验证，也不授予执行资格。 */
export default function ModelPlanManager(props: Props) {
  return <Manager key={props.userId} {...props}/>;
}

function Manager({userId, onUnsafeChange}: Props) {
  const context = useMemo(() => ({userId, scope: {tenantId: 'control-admin', workspaceId: 'model-plans'}}), [userId]);
  const [restored] = useState(() => readOperation<Saved>(context, 'model-plan-configuration', ''));
  const operation = useRef<Operation<Saved> | null>(restored.value);
  const [pending, setPending] = useState(operation.current);
  const [blocked, setBlocked] = useState(!!restored.warning);
  const [view, setView] = useState<ModelPlanView>();
  const [connections, setConnections] = useState<ConnectionView>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [stale, setStale] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form] = Form.useForm<Fields>();
  const live = useRef(true);
  const writing = useRef(false);
  const reader = useRef<AbortController | null>(null);
  const unsafe = dirty || saving || !!pending || blocked;
  const locked = loading || saving || !!pending || blocked;
  const enabled = connections?.connections.filter(c => c.state === 'ENABLED' && c.providerId === 'deepseek'
    && c.protocol.id === 'chat-completions-sse' && c.protocol.version === 'v1') ?? [];
  useEffect(() => {
    onUnsafeChange?.(unsafe);
  }, [unsafe, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {
      if (unsafe) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', protect);
    return () => window.removeEventListener('beforeunload', protect);
  }, [unsafe]);

  function failure(value: unknown) {
    if (value instanceof AiApiError) {
      if (value.httpStatus === 401) return '登录已失效，请重新登录后确认原模型方案操作。';
      if (value.httpStatus === 403) return '只有平台配置管理员可以审核模型与费率；租户和空间角色不代替此资格。';
      if (value.httpStatus === 400) return '方案未发布，请检查连接状态、容量、币种和十进制价格。';
      if (value.httpStatus === 404) return '当前部署尚未开放模型与费率管理，请同步更新后端并使用 DATABASE 模式。';
      if (value.code === 'PLATFORM_IDEMPOTENCY_CONFLICT') return '原操作参数不一致，请保留原记录并核对，不能换键创建竞争操作。';
      if (value.httpStatus === 409) return '目录已变化，草稿已保留。请重新读取并核对，再明确发布。';
    }
    return '请求结果暂时无法确认，请保留原方案并使用原操作键重试确认。';
  }

  async function load() {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    setLoading(true);
    setError('');
    try {
      const [plans, links] = await Promise.all([getModelPlans({signal: controller.signal}), getConnections({signal: controller.signal})]);
      if (!live.current || controller.signal.aborted) return false;
      if (plans.version !== links.version) throw new AiApiError(409, {code: 'CATALOG_CHANGED'});
      setView(plans);
      setConnections(links);
      setStale(false);
      return true;
    } catch (value) {
      if (live.current && !controller.signal.aborted) {
        setView(undefined);
        setConnections(undefined);
        setError(failure(value));
      }
      return false;
    } finally {
      if (live.current && !controller.signal.aborted) setLoading(false);
    }
  }

  useEffect(() => {
    live.current = true;
    const original = operation.current?.request.command.settings;
    if (original) form.setFieldsValue({...original, connectionKey: refKey(original.connection)});
    void load();
    return () => {
      live.current = false;
      reader.current?.abort();
    };
  }, []);

  async function run(command?: ModelPlanCommand) {
    if (!live.current || writing.current || blocked || (command && operation.current)) return;
    const original = operation.current ?? (command ? {
      key: crypto.randomUUID(),
      request: {scope: context.scope, command}
    } : null);
    if (!original) return;
    if (!saveOperation(context, 'model-plan-configuration', '', original)) {
      setBlocked(true);
      return;
    }
    operation.current = original;
    setPending(original);
    writing.current = true;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await publishModelPlan(original.request.command, original.key);
      if (!live.current) return;
      operation.current = null;
      setPending(null);
      setBlocked(!saveOperation(context, 'model-plan-configuration', '', null));
      form.resetFields();
      setDirty(false);
      const refreshed = await load();
      if (live.current) setNotice(refreshed ? '模型与费率已发布到数据库，旧版本和预算保持原值。'
        : '模型与费率已确认发布，目录重读失败；请重新读取，不要重复发布。');
    } catch (value) {
      if (!live.current) return;
      const uncertain = !(value instanceof AiApiError) || value.httpStatus < 300 || value.httpStatus >= 500
        || [401, 403, 408].includes(value.httpStatus) || value.code === 'PLATFORM_IDEMPOTENCY_CONFLICT';
      if (!uncertain) {
        operation.current = null;
        setPending(null);
        setDirty(true);
        setBlocked(!saveOperation(context, 'model-plan-configuration', '', null));
      }
      if (value instanceof AiApiError && value.httpStatus === 409) setStale(true);
      if (value instanceof AiApiError && [401, 403, 404].includes(value.httpStatus)) {
        setView(undefined);
        setConnections(undefined);
      }
      setError(failure(value));
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  function edit(plan: ManagedModelPlan) {
    form.setFieldsValue({
      id: plan.planRef.id.startsWith('reviewed-') ? plan.planRef.id.slice(9) : '',
      connectionKey: refKey(plan.connection),
      remoteModel: plan.remoteModel,
      contextWindowTokens: plan.contextWindowTokens,
      currency: plan.currency,
      inputPerMillion: plan.inputPerMillion,
      outputPerMillion: plan.outputPerMillion,
      usageReporting: plan.usageReporting, toolCalls: plan.toolCalls
    });
    setDirty(true);
    setNotice('');
  }

  return <Flex vertical gap={16} aria-label="审核模型与费率管理">
    <Typography.Paragraph>平台管理员审核远端模型、容量与费率后发布方案。个人模型继续使用本人密钥验证并发布；登记方案不探测供应商、不分配预算，也不会直接成为聊天候选。</Typography.Paragraph>
    {blocked &&
      <Alert showIcon type="warning" title="恢复记录无法可靠保存或读取，已阻止新操作；请保留本页并检查浏览器存储。"/>}
    {error && <Alert showIcon type="warning" title={error}/>}{notice && <Alert showIcon type="info" title={notice}/>}
    {pending && <Card size="small" title="待确认模型方案">
      <Typography.Paragraph>{pending.request.command.settings.remoteModel} ·
        保留完整请求和原操作键，刷新后不自动提交。</Typography.Paragraph>
      <Button aria-label="重试确认原方案" loading={saving} disabled={blocked}
              onClick={() => void run()}>重试确认原方案</Button>
    </Card>}
    <Button aria-label="重新读取方案与连接" loading={loading} disabled={saving || !!pending || blocked}
            onClick={() => void load()}>重新读取方案与连接（保留草稿）</Button>
    {view && <>
      <Typography.Text type="secondary">目录版本 {view.version} ·
        文本／流式模型；能力声明须由审核者核实。</Typography.Text>
      {!enabled.length && <Alert type="info" title="请先在审核连接管理发布并启用一个受支持连接，然后重新读取。"/>}
      <Card size="small" title="发布模型及费率新版本">
        <Form form={form} name="ai-model-plan" layout="vertical" initialValues={defaults} disabled={locked}
              onValuesChange={() => setDirty(true)} onFinish={values => {
          if (stale) return;
          const connection = enabled.find(c => refKey(c.reference) === values.connectionKey)?.reference;
          const {connectionKey: _key, ...fields} = values;
          const settings = modelPlanSettingsSchema.safeParse({...fields, connection});
          if (!settings.success) {
            setError('请填写方案标识、有效连接、远端模型、容量及精确十进制价格。');
            return;
          }
          void run({expectedVersion: view.version, settings: settings.data});
        }}>
          <Form.Item name="id" label="方案标识"
                     rules={[{required: true, pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/}]}><Input
            aria-label="方案标识" maxLength={96}/></Form.Item>
          <Form.Item name="connectionKey" label="精确审核连接" rules={[{required: true}]}><Select
            aria-label="精确审核连接"
            options={enabled.map(c => ({
              value: refKey(c.reference),
              label: `${c.providerId} · ${c.reference.id}@${c.reference.version}`
            }))}/></Form.Item>
          <Form.Item name="remoteModel" label="远端模型名" rules={[{required: true, whitespace: true, max: 256}]}><Input
            aria-label="方案远端模型名" maxLength={256}/></Form.Item>
          <Form.Item name="contextWindowTokens" label="审核上下文容量（Token）" rules={[{required: true}]}><InputNumber
            aria-label="审核上下文容量" min={2} max={10_000_000} precision={0}/></Form.Item>
          <Form.Item name="currency" label="币种" rules={[{required: true}]}><Select aria-label="费率币种"
                                                                                     options={['CNY', 'USD', 'EUR'].map(value => ({value}))}/></Form.Item>
          <Form.Item name="inputPerMillion" label="每百万输入 Token 价格" rules={[{required: true}]}><InputNumber
            aria-label="输入单价" stringMode min="0"/></Form.Item>
          <Form.Item name="outputPerMillion" label="每百万输出 Token 价格" rules={[{required: true}]}><InputNumber
            aria-label="输出单价" stringMode min="0"/></Form.Item>
          <Form.Item name="usageReporting" label="声明支持用量报告" valuePropName="checked"><Switch
            aria-label="声明支持用量报告"/></Form.Item>
          <Form.Item name="toolCalls" label="声明支持工具调用" valuePropName="checked"><Switch
            aria-label="声明支持工具调用"/></Form.Item>
          <Typography.Paragraph
            type="secondary">能力声明需要审核者核实；连接探测不证明工具调用能力，程序还会核验适配支持。</Typography.Paragraph>
          <Typography.Paragraph
            type="secondary">价格按原币种十进制保存，不自动换汇。修改价格、容量或目标均追加新固定版本；旧模型、历史调用及预算不会自动升级。</Typography.Paragraph>
          <Flex gap={8} wrap><Button aria-label="发布模型与费率版本" type="primary" htmlType="submit" loading={saving}
                                     disabled={locked || stale || !enabled.length}>发布模型与费率版本</Button>
            <Popconfirm title="放弃未保存的方案修改？" onConfirm={() => {
              form.resetFields();
              setDirty(false);
            }}><Button disabled={locked}>放弃方案修改</Button></Popconfirm></Flex>
        </Form>
      </Card>
      {!view.plans.length && <Typography.Text>尚无审核模型方案，可以从已有审核连接独立创建。</Typography.Text>}
      {[...view.plans].reverse().map(plan => <Card size="small" key={refKey(plan.planRef)}
                                                   title={`${plan.remoteModel} · ${plan.planRef.id}@${plan.planRef.version}`}>
        <Flex vertical gap={8}><Typography.Text>{plan.connection.id}@{plan.connection.version} ·
          上下文 {plan.contextWindowTokens} Token</Typography.Text>
          <Typography.Text>输入 {plan.inputPerMillion} / 输出 {plan.outputPerMillion} {plan.currency}／百万 Token ·
            费率 {plan.rate.id}@{plan.rate.version}</Typography.Text>
          <Button disabled={locked || dirty} onClick={() => edit(plan)}>参考此版本编辑方案</Button></Flex>
      </Card>)}
    </>}
  </Flex>;
}
