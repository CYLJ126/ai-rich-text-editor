import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Form, Input, InputNumber, Popconfirm, Select, Tag, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai/request';
import {
  type ConnectionCommand,
  type ConnectionSettings,
  connectionSettingsSchema,
  type ConnectionView,
  executeConnectionCommand,
  getConnections,
  type ManagedConnection,
} from '@/services/platform/connection';
import {type Operation, readOperation, saveOperation} from './storage';
import MasterDetailLayout from '@/components/MasterDetailLayout';

type Saved = { scope: { tenantId: string; workspaceId: string }; command: ConnectionCommand };
type Props = { userId: string; onUnsafeChange?: (unsafe: boolean) => void };
const defaults: ConnectionSettings = {
  id: '',
  providerId: 'deepseek',
  endpoint: '',
  connectTimeoutSeconds: 5,
  responseTimeoutSeconds: 120,
  maxResponseBytes: 1048576,
};

/** 平台审核连接独立于空间 AI 准备；管理资格以服务端当前 RBAC 为准。 */
export default function ConnectionManager(props: Props) {
  return <Manager key={props.userId} {...props}/>;
}

function Manager({userId, onUnsafeChange}: Props) {
  const context = useMemo(() => ({userId, scope: {tenantId: 'control-admin', workspaceId: 'connections'}}), [userId]);
  const [restored] = useState(() => readOperation<Saved>(context, 'connection-configuration', ''));
  const pendingRef = useRef<Operation<Saved> | null>(restored.value);
  const [pending, setPending] = useState(pendingRef.current);
  const [blocked, setBlocked] = useState(!!restored.warning);
  const [view, setView] = useState<ConnectionView>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const writing = useRef(false);
  const [dirty, setDirty] = useState(false);
  const [editing, setEditing] = useState(!!restored.value);
  const [selected, setSelected] = useState<string>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form] = Form.useForm<ConnectionSettings>();
  const live = useRef(true);
  const reader = useRef<AbortController | null>(null);
  const unsafe = dirty || !!pending || blocked || saving;
  const disabled = !view || loading || saving || !!pending || blocked;

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

  function failure(error: unknown) {
    if (error instanceof AiApiError) {
      if (error.httpStatus === 401) return '登录已失效，请重新登录后确认原操作。';
      if (error.httpStatus === 403) return '只有平台配置管理员可以管理审核连接；租户或空间角色不会自动获得此权限。';
      if (error.httpStatus === 409) return '目录版本已变化或原操作参数不一致，请确认原操作或重新读取目录后修改。';
      if (error.httpStatus === 400) return '连接未保存，请检查字段、受支持供应商以及部署允许的 HTTP origin。';
      if (error.httpStatus === 404) return '当前部署尚未开放数据库连接管理，请确认后端版本与 DATABASE 模式。';
    }
    return '请求结果暂时无法确认；若有待确认操作，请按原参数和原操作键重试。';
  }

  async function load() {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    setLoading(true);
    setError('');
    try {
      const value = await getConnections({signal: controller.signal});
      if (live.current && !controller.signal.aborted)
        setView(previous => previous && previous.version > value.version ? previous : value);
    } catch (error) {
      if (live.current && !controller.signal.aborted) {
        setError(failure(error));
        setView(undefined);
      }
    } finally {
      if (live.current && !controller.signal.aborted) setLoading(false);
    }
  }

  useEffect(() => {
    live.current = true;
    const command = pendingRef.current?.request.command;
    if (command?.kind === 'publish') form.setFieldsValue(command.request.settings);
    void load();
    return () => {
      live.current = false;
      reader.current?.abort();
    };
  }, []);

  async function run(command?: ConnectionCommand) {
    if (writing.current || blocked || !live.current) return;
    if (pendingRef.current && command) return;
    const operation = pendingRef.current ?? (command ? {
      key: crypto.randomUUID(),
      request: {scope: context.scope, command}
    } : null);
    if (!operation) return;
    if (!saveOperation(context, 'connection-configuration', '', operation)) {
      setBlocked(true);
      return;
    }
    pendingRef.current = operation;
    setPending(operation);
    writing.current = true;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const value = await executeConnectionCommand(operation.request.command, operation.key);
      if (!live.current) return;
      setView(previous => previous && previous.version > value.version ? previous : value);
      pendingRef.current = null;
      setPending(null);
      setBlocked(!saveOperation(context, 'connection-configuration', '', null));
      setDirty(false);
      form.resetFields();
      setEditing(false);
      setSelected(undefined);
      setNotice('连接操作已确认并保存到数据库；后续读取与启动使用数据库目录。');
    } catch (error) {
      if (!live.current) return;
      const uncertain = !(error instanceof AiApiError) || error.httpStatus >= 500 || error.httpStatus < 300
        || [401, 403, 408].includes(error.httpStatus) || error.code === 'PLATFORM_IDEMPOTENCY_CONFLICT';
      if (!uncertain) {
        pendingRef.current = null;
        setPending(null);
        setBlocked(!saveOperation(context, 'connection-configuration', '', null));
      }
      setError(failure(error));
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  function edit(connection: ManagedConnection) {
    if (disabled || dirty) return;
    setEditing(true);
    setSelected(JSON.stringify(connection.reference));
    form.setFieldsValue({
      id: connection.reference.id,
      providerId: connection.providerId,
      endpoint: connection.endpoint,
      connectTimeoutSeconds: connection.connectTimeoutSeconds,
      responseTimeoutSeconds: connection.responseTimeoutSeconds,
      maxResponseBytes: connection.maxResponseBytes
    });
    setDirty(false);
    setNotice('');
  }

  return <MasterDetailLayout
    label="数据库连接管理"
    actions={[
      {
        key: 'refresh',
        label: '重新读取连接目录',
        icon: 'ReloadOutlined',
        loading,
        disabled: saving || !!pending || blocked,
        onClick: () => void load()
      },
      {
        key: 'add',
        label: '创建连接新版本',
        icon: 'PlusOutlined',
        disabled: disabled || dirty,
        onClick: () => {
          setSelected(undefined);
          setEditing(true);
          form.resetFields();
          setDirty(false);
          setNotice('');
        }
      },
    ]}
    selectedKey={selected} onSelect={key => {
    const connection = view?.connections.find(c => JSON.stringify(c.reference) === key);
    if (connection) edit(connection);
  }}
    emptyText="尚无审核连接。"
    listHeader={view && <Typography.Paragraph type="secondary">目录版本 {view.version} · 部署允许的
      origin：{view.allowedOrigins.join('、') || '未配置，当前不能发布连接'}</Typography.Paragraph>}
    items={[...(view?.connections ?? [])].reverse().map(connection => ({
      key: JSON.stringify(connection.reference),
      label: `以此版本编辑 ${connection.reference.id}@${connection.reference.version}`,
      disabled: disabled || dirty,
      content: <Flex vertical gap={8}>
        <Typography.Text
          strong>{connection.providerId} · {connection.reference.id}@{connection.reference.version}</Typography.Text>
        <Tag
          color={connection.state === 'ENABLED' ? 'green' : 'default'}>{connection.state === 'ENABLED' ? '已启用' : '已停用'}</Tag>
        <Typography.Text>{connection.endpoint}</Typography.Text>
        <Typography.Text
          type="secondary">{connection.protocol.id}@{connection.protocol.version} ·
          连接 {connection.connectTimeoutSeconds}s ·
          响应 {connection.responseTimeoutSeconds}s</Typography.Text>
      </Flex>
    }))}>
    <Typography.Paragraph>
      审核连接由平台配置管理员发布：<br/>
      1. 第一批支持 DeepSeek 的 Chat Completions SSE 协议；<br/>
      2. 连接不保存 API Key，模型草稿另行配置本人凭据并验证。
    </Typography.Paragraph>
    {blocked &&
      <Alert type="warning" showIcon title="恢复记录无法可靠保存或读取，已阻止新操作；请保留本页并检查浏览器存储。"/>}
    {error && <Alert type="warning" showIcon title={error}/>}
    {notice && <Alert type="success" showIcon title={notice}/>}
    {pending && <Card title="待确认连接操作" size="small">
      <Typography.Paragraph>按原参数和原操作键重试；不会重新生成连接版本。刷新页面后也应先确认这项操作。</Typography.Paragraph>
      <Button loading={saving} disabled={blocked} onClick={() => void run()}>重试确认原连接操作</Button>
    </Card>}
    {view && !view.initialized && <Alert type="info" title="数据库目录为空，可以直接创建第一个审核连接。"/>}
    {view && !editing && <Typography.Paragraph>选择连接版本查看或编辑，点击添加图标创建新版本。</Typography.Paragraph>}
    {view && editing && <>
      <Card title="创建连接／发布连接新版本" size="small">
        <Typography.Paragraph
          type="secondary">相同连接标识保存为新版本；旧版本保留。此步骤只登记连接，不会向供应商发送请求。</Typography.Paragraph>
        <Form form={form} name="ai-connection-management" initialValues={defaults} layout="vertical"
              disabled={disabled || (selected !== undefined && view.connections.find(c => JSON.stringify(c.reference) === selected)?.providerId !== 'deepseek')}
              onValuesChange={() => setDirty(true)} onFinish={values => {
          const result = connectionSettingsSchema.safeParse(values);
          if (!result.success) {
            setError('请填写有效的连接标识、基础地址和范围内的连接参数。');
            return;
          }
          void run({kind: 'publish', request: {expectedVersion: view.version, settings: result.data}});
        }}>
          <Form.Item name="id" label="连接标识" rules={[{required: true, whitespace: true, max: 256}]}><Input
            aria-label="连接标识"/></Form.Item>
          <Form.Item name="providerId" label="供应商"><Select aria-label="供应商" options={[{
            value: 'deepseek',
            label: 'DeepSeek'
          }]}/></Form.Item>
          <Form.Item name="endpoint" label="API 基础地址" rules={[{required: true, type: 'url'}]}><Input
            aria-label="API 基础地址" placeholder="https://api.deepseek.com/v1" maxLength={2048}/></Form.Item>
          <Flex gap={12} wrap>
            <Form.Item name="connectTimeoutSeconds" label="连接超时（秒）" rules={[{required: true}]}><InputNumber
              aria-label="连接超时（秒）" min={1} max={60} precision={0}/></Form.Item>
            <Form.Item name="responseTimeoutSeconds" label="响应超时（秒）" rules={[{required: true}]}><InputNumber
              aria-label="响应超时（秒）" min={1} max={900} precision={0}/></Form.Item>
            <Form.Item name="maxResponseBytes" label="响应字节上限" rules={[{required: true}]}><InputNumber
              aria-label="响应字节上限" min={1} max={33554432} precision={0}/></Form.Item>
          </Flex>
          <Flex gap={8} wrap><Button htmlType="submit" type="primary" loading={saving}>发布连接版本</Button>
            <Button onClick={() => {
              form.resetFields();
              setDirty(false);
              setEditing(false);
              setSelected(undefined);
            }}>放弃连接修改</Button></Flex>
        </Form>
      </Card>
      {selected && view.connections.filter(c => JSON.stringify(c.reference) === selected).map(connection => <Popconfirm
        key={selected}
        title={connection.state === 'ENABLED' ? '停用此连接的全部版本？引用它的个人模型将无法通过当前模板检查。' : '启用此连接？仍会检查租户、空间政策和模型资格。'}
        onConfirm={() => run({
          kind: 'state',
          request: {expectedVersion: view.version, id: connection.reference.id, enabled: connection.state !== 'ENABLED'}
        })}>
        <Button disabled={disabled || dirty}>{connection.state === 'ENABLED' ? '停用连接' : '启用连接'}</Button>
      </Popconfirm>)}
    </>}
  </MasterDetailLayout>;
}
