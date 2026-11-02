import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Descriptions, Flex, Form, Popconfirm, Switch, Table, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai/request';
import {
  executePolicyCommand,
  getPolicy,
  type PolicyCommand,
  policyCommandSchema,
  type PolicyKind,
  type PolicySnapshot,
  type TenantPolicyView
} from '@/services/platform/policy';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {type PolicyOperation, readPolicyOperation, savePolicyOperation} from './policyRecovery';
import {
  deepSeekDraft,
  fromPolicyForm,
  policyChanges,
  type PolicyFormValues,
  policyRulesForm,
  toPolicyForm
} from './policyForm';
import PolicyRulesFields from './PolicyRulesFields';

type Props = {
  userId: string; scope: WorkspaceScope; kind: PolicyKind; workspaceName?: string; refreshRevision?: number;
  ceiling?: TenantPolicyView | null;
  onUnsafeChange?: (unsafe: boolean) => void; onSaved?: () => void;
  onSnapshot?: (snapshot: PolicySnapshot | null) => void;
};

/** 租户和空间各自核验、读取及恢复；一个范围的拒绝不影响另一范围。 */
export default function PolicyEditor(props: Props) {
  return <Editor
    key={JSON.stringify([props.userId, props.scope.tenantId, props.kind === 'workspace' ? props.scope.workspaceId : '', props.kind])} {...props}/>;
}

function Editor({
                  userId,
                  scope,
                  kind,
                  workspaceName,
                  refreshRevision = 0,
                  ceiling,
                  onUnsafeChange,
                  onSaved,
                  onSnapshot
                }: Props) {
  const [form] = Form.useForm<PolicyFormValues>();
  const draft = Form.useWatch([], {form, preserve: true}) as PolicyFormValues | undefined;
  const [restored] = useState(() => readPolicyOperation(userId, scope, kind));
  const pendingRef = useRef<PolicyOperation | null>(restored.operation);
  const [pending, setPending] = useState(pendingRef.current);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [snapshot, setSnapshot] = useState<PolicySnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [stale, setStale] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const reader = useRef<AbortController | null>(null);
  const live = useRef(true);
  const writing = useRef(false);
  const callbacks = useRef({onSaved, onSnapshot});
  callbacks.current = {onSaved, onSnapshot};
  const state = useRef({dirty, pending, blocked});
  state.current = {dirty, pending, blocked};
  const unsafe = dirty || !!pending || blocked || saving;
  const locked = loading || saving || !!pending || blocked;
  const changes = snapshot ? policyChanges(snapshot, draft) : [];

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
      if (['PLATFORM_SPACE_READ_ONLY', 'PLATFORM_SPACE_CLOSING', 'PLATFORM_TENANT_INACTIVE'].includes(error.code ?? ''))
        return '当前租户或空间不允许修改政策，请选择处于正常状态的空间。';
      if (error.code === 'PLATFORM_POLICY_DENIED') return '空间政策超出租户上限。请收紧空间规则，或先由租户治理者调整租户政策。';
      if (error.code === 'PLATFORM_IDEMPOTENCY_CONFLICT') return '原操作内容不一致，请保留原操作记录并核对；不会生成竞争操作。';
      if (error.httpStatus === 401) return '登录已失效，请重新登录后确认原政策操作。';
      if (error.httpStatus === 403) return kind === 'tenant'
        ? '当前无权读取或修改租户政策；需要租户负责人或管理员的治理资格。空间政策可单独检查。'
        : '当前无权读取或修改空间政策；需要当前空间负责人的治理资格。';
      if (error.httpStatus === 404) return '政策目标不可访问或当前部署未开放政策治理，请检查当前租户和空间。';
      if (error.httpStatus === 409) return '政策或父记录版本已变化，草稿已保留。请读取最新版本并核对差异后再保存。';
      if (error.httpStatus === 400) return '政策未保存，请检查完整规则、额外角色及字段内容。';
      if (error.code === 'INVALID_POLICY_RESPONSE') return '政策响应与当前范围或支持的结构不一致，已阻止编辑；请确认前后端版本并重新读取。';
    }
    return '政策请求结果暂时无法确认；如有待确认操作，请使用原参数和原操作键重试。';
  }

  async function load(keepDraft = false) {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    const preserved = keepDraft ? form.getFieldsValue(true) : null;
    setLoading(true);
    setError('');
    setNotice('');
    try {
      const value = await getPolicy(kind, scope, {signal: controller.signal});
      if (!live.current || controller.signal.aborted) return false;
      setSnapshot(value);
      const original = pendingRef.current?.command;
      form.setFieldsValue(preserved ?? (original ? policyRulesForm(original.request.rules,
        original.kind === 'tenant' ? original.request.membersMayCreateWorkspace : undefined) : toPolicyForm(value)));
      setDirty(keepDraft);
      setStale(false);
      if (preserved) setNotice('已读取最新版本并保留完整草稿。保存将以草稿替换当前政策，请核对下方变更预览。');
      callbacks.current.onSnapshot?.(value);
      return true;
    } catch (error) {
      if (live.current && !controller.signal.aborted) {
        setSnapshot(null);
        setError(failure(error));
        callbacks.current.onSnapshot?.(null);
      }
      return false;
    } finally {
      if (live.current && !controller.signal.aborted) setLoading(false);
    }
  }

  useEffect(() => {
    live.current = true;
    void load();
    return () => {
      live.current = false;
      reader.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!refreshRevision) return;
    if (state.current.dirty || state.current.pending || state.current.blocked) {
      setStale(true);
      setNotice('租户政策上限已更新，当前草稿或原操作已保留，请重新核对后继续。');
    } else void load();
  }, [refreshRevision]);

  async function run(command?: PolicyCommand) {
    if (!live.current || writing.current || blocked || (command && pendingRef.current)) return;
    const operation = pendingRef.current ?? (command ? {
      schemaVersion: 1 as const,
      key: crypto.randomUUID(),
      command
    } : null);
    if (!operation) return;
    if (!savePolicyOperation(userId, scope, kind, operation)) {
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
      await executePolicyCommand(operation.command, operation.key);
      if (!live.current) return;
      pendingRef.current = null;
      setPending(null);
      setBlocked(!savePolicyOperation(userId, scope, kind, null));
      setDirty(false);
      const refreshed = await load();
      if (!live.current) return;
      setNotice(refreshed ? '政策已保存，已重新读取当前版本。' : '政策已确认保存，但最新政策读取失败，请重新读取后继续。');
      callbacks.current.onSaved?.();
    } catch (error) {
      if (!live.current) return;
      const uncertain = !(error instanceof AiApiError) || error.httpStatus >= 500 || error.httpStatus < 300
        || [401, 408].includes(error.httpStatus) || error.httpStatus === 403 && error.code !== 'PLATFORM_POLICY_DENIED'
        || error.code === 'PLATFORM_IDEMPOTENCY_CONFLICT';
      if (!uncertain) {
        pendingRef.current = null;
        setPending(null);
        setBlocked(!savePolicyOperation(userId, scope, kind, null));
      }
      if (error instanceof AiApiError && error.httpStatus === 409 && error.code !== 'PLATFORM_IDEMPOTENCY_CONFLICT') setStale(true);
      if (error instanceof AiApiError && [401, 403, 404].includes(error.httpStatus) && error.code !== 'PLATFORM_POLICY_DENIED') {
        setSnapshot(null);
        callbacks.current.onSnapshot?.(null);
      }
      setError(failure(error));
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  function submit(values: PolicyFormValues) {
    if (!snapshot || locked || stale) return;
    try {
      const rules = fromPolicyForm(values);
      const command = policyCommandSchema.parse(snapshot.kind === 'tenant' ? {
        kind: 'tenant', request: {
          tenantId: scope.tenantId,
          expectedTenantVersion: snapshot.value.tenantVersion,
          expectedPolicyVersion: snapshot.value.policy.version,
          rules,
          membersMayCreateWorkspace: values.membersMayCreateWorkspace,
        }
      } : {
        kind: 'workspace', request: {
          scope, expectedWorkspaceVersion: snapshot.value.workspaceVersion,
          expectedPolicyVersion: snapshot.value.policy.version, rules
        }
      });
      void run(command);
    } catch (error) {
      setError(error instanceof Error && !('issues' in error) ? error.message : '请检查完整政策规则；额外角色只能关联已允许创建的资源类型。');
    }
  }

  const title = !snapshot ? (kind === 'tenant' ? '租户政策' : '空间政策') : kind === 'tenant'
    ? `${snapshot.value.tenantName} · ${snapshot.value.tenantType === 0 ? '个人租户政策' : '组织租户政策'}`
    : `${workspaceName ?? '当前空间'} · 空间政策`;
  return <section aria-label={kind === 'tenant' ? '租户政策编辑' : '空间政策编辑'}>
    <Flex vertical gap={12}>
      <Typography.Title level={4}>{title}</Typography.Title>
      <Typography.Paragraph>{kind === 'tenant'
        ? '租户政策限定其下空间的权限上限。修改不会自动重写已有空间政策，个人租户沿用同一套治理规则。'
        : '空间政策只能在租户政策上限内调整。即使无法读取租户完整政策，仍可按当前空间治理资格编辑，保存时由服务端核验上限。'}</Typography.Paragraph>
      {blocked && <Alert type="warning" showIcon
                         title="政策恢复记录无法可靠保存或读取，已阻止新操作。请保留本页并检查浏览器存储。"/>}
      {error && <Alert type="warning" showIcon title={error}/>}
      {notice && <Alert type={stale ? 'warning' : 'info'} showIcon title={notice}/>}
      {pending && <Card title="待确认政策操作" size="small">
        <Typography.Paragraph>保留原目标、完整规则和操作键；刷新后不自动提交，重试不会重复更新政策。</Typography.Paragraph>
        <Button aria-label="重试确认原政策操作" loading={saving} disabled={blocked}
                onClick={() => void run()}>重试确认原政策操作</Button>
      </Card>}
      <Flex gap={8} wrap>
        {dirty ? <>
          <Button aria-label="读取最新版本并保留草稿" disabled={locked}
                  onClick={() => void load(true)}>读取最新版本并保留草稿</Button>
          <Popconfirm title="放弃未保存修改并读取当前政策？" onConfirm={() => load()}>
            <Button disabled={locked}>放弃修改并重新读取</Button>
          </Popconfirm>
        </> : <Button aria-label="重新读取政策" loading={loading} disabled={saving || !!pending || blocked}
                      onClick={() => void load()}>重新读取政策</Button>}
      </Flex>
      {snapshot && <>
        <Descriptions size="small" column={2} items={[
          {key: 'version', label: '政策版本', children: snapshot.value.policy.version},
          {
            key: 'parent', label: snapshot.kind === 'tenant' ? '租户版本' : '空间版本',
            children: snapshot.kind === 'tenant' ? snapshot.value.tenantVersion : snapshot.value.workspaceVersion
          },
          ...(snapshot.kind === 'workspace' ? [{
            key: 'ceiling',
            label: '租户政策上限版本',
            children: snapshot.value.tenantPolicyVersion
          }] : []),
        ]}/>
        {kind === 'workspace' && ceiling?.policy.tenantId === scope.tenantId &&
          <Alert type="info" title="当前可读取的租户上限"
                 description={`个人凭据：${ceiling.policy.rules.personalCredentialsAllowed ? '允许' : '禁止'}；供应商：${ceiling.policy.rules.allowedProviders.join('、') || '无'}；主机：${ceiling.policy.rules.allowedDestinations.join('、') || '无'}。保存仍以服务端当前政策为准。`}/>}
        <Card size="small">
          <Form form={form} name={`workspace-policy-${kind}`} layout="vertical" disabled={locked}
                onValuesChange={() => {
                  setDirty(true);
                  setError('');
                }} onFinish={() => submit(form.getFieldsValue(true))}>

            {kind === 'tenant' &&
              <Form.Item name="membersMayCreateWorkspace" label="允许成员创建工作空间" valuePropName="checked">
                <Switch aria-label="允许成员创建工作空间"/>
              </Form.Item>}

            <Button disabled={locked} onClick={() => {
              form.setFieldsValue(deepSeekDraft(form.getFieldsValue(true)));
              setDirty(true);
              setError('');
            }}>填入 DeepSeek 个人模型规则</Button>

            <Typography.Paragraph
              type="secondary">只填充草稿，保留已有名单和其他规则。保存不会代替模型授权，也不会扩大部署出口范围。</Typography.Paragraph>

            {/* 表单字段 */}
            <PolicyRulesFields form={form}/>

            {(dirty || pending) && <>
              <Typography.Title level={5}>保存前变更预览</Typography.Title>
              <Table size="small" pagination={false} dataSource={changes} scroll={{x: true}} columns={[
                {title: '项目', dataIndex: 'field'},
                {title: '当前服务器政策', dataIndex: 'current'},
                {title: '完整草稿', dataIndex: 'draft'},
              ]}/>
            </>}

            <Button type="primary" htmlType="submit" loading={saving} disabled={locked || stale || !dirty}>
              {kind === 'tenant' ? '保存租户政策' : '保存空间政策'}
            </Button>
          </Form>
        </Card>
      </>}
    </Flex>
  </section>;
}
