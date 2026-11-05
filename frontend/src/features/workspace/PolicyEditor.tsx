import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Descriptions, Flex, Form, Popconfirm, Table, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai/request';
import {
  executePolicyCommand,
  getPolicy,
  getPolicyConnectionOptions,
  type PolicyCommand,
  policyCommandSchema,
  type PolicyConnectionOptions,
  type PolicyField,
  type PolicyKind,
  type PolicyPreview,
  type PolicyRules,
  type PolicyScope,
  type PolicySnapshot,
  previewWorkspacePolicy,
  type TenantPolicyView,
  type WorkspacePolicyView
} from '@/services/platform/policy';
import {type PolicyOperation, readPolicyOperation, savePolicyOperation} from './policyRecovery';
import {
  deepSeekDraft,
  fromPolicyForm,
  policyChanges,
  policyFieldNames,
  type PolicyFormValues,
  policyRulesForm,
  toPolicyForm
} from './policyForm';
import PolicyRulesFields from './PolicyRulesFields';
import PolicyEffectTable from './PolicyEffectTable';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {confirmedWorkspaceVersion, type WorkspaceVersionChange} from './workspaceVersionChange';

type Props = {
  userId: string; scope: PolicyScope; kind: PolicyKind; workspaceName?: string; refreshRevision?: number;
  versionChange?: WorkspaceVersionChange;
  ceiling?: TenantPolicyView | null;
  focus?: { field: PolicyField };
  onUnsafeChange?: (unsafe: boolean) => void; onSaved?: (change?: WorkspaceVersionChange) => void;
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
                  versionChange, ceiling, focus,
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
  const [directory, setDirectory] = useState<PolicyConnectionOptions>();
  const [directoryFailed, setDirectoryFailed] = useState(false);
  const [preview, setPreview] = useState<PolicyPreview | null>(null);
  const previewReader = useRef<AbortController | null>(null);
  const [previewing, setPreviewing] = useState(false);
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
  const unsafe = dirty || !!pending || blocked || saving || previewing;
  const locked = loading || saving || !!pending || blocked || previewing;
  const changes = snapshot ? policyChanges(snapshot, draft) : [];
  const ceilingChanged = snapshot?.kind === 'workspace' && !!ceiling
    && ceiling.policy.version > snapshot.value.tenantPolicyVersion;
  const requiresRefresh = stale || ceilingChanged;
  useConfigurationDraft({
    group: kind === 'tenant' ? 'tenant-policy' : 'space-policy', label: kind === 'tenant' ? '租户政策' : '空间政策',
    order: kind === 'tenant' ? 10 : 20,
    status: blocked ? 'blocked' : saving || previewing ? 'submitting' : pending ? 'pending' : dirty ? 'dirty' : 'clean',
    save: async () => {
      try {
        return await submit(await form.validateFields());
      } catch {
        return false;
      }
    },
    discard: () => load()
  });

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

  function clearPreview() {
    previewReader.current?.abort();
    previewReader.current = null;
    setPreviewing(false);
    setPreview(null);
  }

  async function load(keepDraft = false) {
    clearPreview();
    setDirectory(undefined);
    setDirectoryFailed(false);
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
      void getPolicyConnectionOptions(kind, scope, {signal: controller.signal}).then(options => {
        if (live.current && !controller.signal.aborted) setDirectory(options);
      }).catch(() => {
        if (live.current && !controller.signal.aborted) setDirectoryFailed(true);
      });
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
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      live.current = false;
      reader.current?.abort();
      clearPreview();
      setDirectory(undefined);
      setSnapshot(null);
      setDirty(false);
      form.resetFields();
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      reader.current?.abort();
      previewReader.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, []);

  useEffect(() => {
    if (!refreshRevision) return;
    if (state.current.dirty || state.current.pending || state.current.blocked) {
      clearPreview();
      setDirectory(undefined);
      setStale(true);
      setNotice('空间政策或租户上限已更新，当前草稿或原操作已保留，请重新核对后继续。');
    } else void load();
  }, [refreshRevision]);

  const workspaceVersion = snapshot?.kind === 'workspace' ? snapshot.value.workspaceVersion : undefined;
  useEffect(() => {
    if (!versionChange || workspaceVersion !== versionChange.from) return;
    clearPreview();
    setSnapshot(value => value?.kind === 'workspace'
      ? {...value, value: {...value.value, workspaceVersion: versionChange.to}} : value);
  }, [versionChange, workspaceVersion]);

  async function run(command?: PolicyCommand) {
    if (!live.current || writing.current || blocked || (command && pendingRef.current)) return false;
    const operation = pendingRef.current ?? (command ? {
      schemaVersion: 1 as const,
      key: crypto.randomUUID(),
      command
    } : null);
    if (!operation) return false;
    if (!savePolicyOperation(userId, scope, kind, operation)) {
      setBlocked(true);
      return false;
    }
    pendingRef.current = operation;
    setPending(operation);
    writing.current = true;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await executePolicyCommand(operation.command, operation.key);
      if (!live.current) return false;
      pendingRef.current = null;
      setPending(null);
      const cleared = savePolicyOperation(userId, scope, kind, null);
      setBlocked(!cleared);
      setDirty(false);
      const refreshed = await load();
      if (!live.current) return false;
      setNotice(refreshed ? '政策已保存，已重新读取当前版本。' : '政策已确认保存，但最新政策读取失败，请重新读取后继续。');
      callbacks.current.onSaved?.(operation.command.kind === 'workspace'
        ? confirmedWorkspaceVersion(operation.command.request.expectedWorkspaceVersion) : undefined);
      return refreshed && cleared;
    } catch (error) {
      if (!live.current) return false;
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
      return false;
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  async function checkDraft(basis: WorkspacePolicyView, rules: PolicyRules) {
    if (previewReader.current) return null;
    const controller = new AbortController();
    previewReader.current = controller;
    setPreviewing(true);
    setPreview(null);
    setError('');
    try {
      const result = await previewWorkspacePolicy(basis, rules, {signal: controller.signal});
      if (!live.current || controller.signal.aborted) return null;
      setPreview(result);
      return result;
    } catch (error) {
      if (live.current && !controller.signal.aborted) {
        if (error instanceof AiApiError && error.httpStatus === 409) {
          setStale(true);
          setError(failure(error));
        } else if (error instanceof AiApiError && error.httpStatus === 400) {
          setError('草稿未通过校验。请核对规则字段，目的地只填写主机名。');
        } else {
          if (error instanceof AiApiError && [401, 403, 404].includes(error.httpStatus)) {
            reader.current?.abort();
            setSnapshot(null);
            setDirectory(undefined);
            callbacks.current.onSnapshot?.(null);
          }
          setError('草稿政策效果未知，未提交保存。请重试预览或重新读取政策。');
        }
      }
      return null;
    } finally {
      if (live.current && previewReader.current === controller) {
        previewReader.current = null;
        setPreviewing(false);
      }
    }
  }

  async function previewForm() {
    if (snapshot?.kind !== 'workspace' || locked || requiresRefresh) return;
    try {
      await checkDraft(snapshot.value, fromPolicyForm(form.getFieldsValue(true)));
    } catch {
      setError('请检查完整政策规则及额外创建角色。');
    }
  }

  async function submit(values: PolicyFormValues) {
    if (!snapshot || locked || requiresRefresh || previewReader.current) return false;
    try {
      const rules = fromPolicyForm(values);
      if (snapshot.kind === 'workspace') {
        const result = await checkDraft(snapshot.value, rules);
        if (!result || result.proposed.limitedFields.length > 0) return false;
      }
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
          expectedPolicyVersion: snapshot.value.policy.version,
          expectedTenantPolicyVersion: snapshot.value.tenantPolicyVersion,
          rules
        }
      });
      return await run(command);
    } catch (error) {
      setError(error instanceof Error && !('issues' in error) ? error.message : '请检查完整政策规则；额外角色只能关联已允许创建的资源类型。');
      return false;
    }
  }

  const deepSeekAvailable = directory?.protocols.includes('chat-completions-sse')
    && directory.providers.includes('deepseek') && directory.hosts.includes('api.deepseek.com');
  const title = !snapshot ? (kind === 'tenant' ? '租户政策' : '空间政策') : kind === 'tenant'
    ? `${snapshot.value.tenantName} · ${snapshot.value.tenantType === 0 ? '个人租户政策' : '组织租户政策'}`
    : `${workspaceName ?? '当前空间'} · 空间政策`;
  return <section aria-label={kind === 'tenant' ? '租户政策编辑' : '空间政策编辑'}>
    <Flex vertical gap={12}>
      <Typography.Title level={4}>{title}</Typography.Title>
      <Typography.Paragraph>{kind === 'tenant'
        ? '租户政策限定其下空间的权限上限。修改不会自动重写已有空间政策，个人租户沿用同一套治理规则。'
        : '空间独立保存规则。服务端会先按当前租户上限预览草稿，再在保存时复核上限。'}</Typography.Paragraph>
      {blocked && <Alert type="warning" showIcon
                         title="政策恢复记录无法可靠保存或读取，已阻止新操作。请保留本页并检查浏览器存储。"/>}
      {error && <Alert type="warning" showIcon title={error}/>}
      {kind === 'workspace' && error && !snapshot && <Alert type="info" title="当前政策效果未知。"/>}
      {notice && <Alert type={stale ? 'warning' : 'info'} showIcon title={notice}/>}
      {ceilingChanged && <Alert type="warning" title="已读到更新的租户上限。请重新读取空间政策后继续。"/>}
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
        {snapshot.kind === 'workspace' && <PolicyEffectTable snapshot={snapshot.value} ceiling={ceiling}
                                                             preview={preview} focus={focus} stale={requiresRefresh}/>}
        <Card size="small" title={kind === 'workspace' ? '本空间设置' : undefined}>
          <Form form={form} name={`workspace-policy-${kind}`} layout="vertical" disabled={locked}
                onValuesChange={() => {
                  setDirty(true);
                  setPreview(null);
                  setError('');
                }} onFinish={() => submit(form.getFieldsValue(true))}>

            {directoryFailed && <Alert type="info" title="常用连接选项暂时无法读取。仍可手动填写并由服务端校验。"/>}
            <PolicyRulesFields form={form} directory={directory} tenant={kind === 'tenant'}
                               example={deepSeekAvailable ? () => {
              form.setFieldsValue(deepSeekDraft(form.getFieldsValue(true)));
              setDirty(true);
                                 setPreview(null);
              setError('');
                               } : undefined}/>

            {(dirty || pending) && <>
              <Typography.Title level={5}>保存前变更预览</Typography.Title>
              <Table size="small" pagination={false} dataSource={changes} scroll={{x: true}} columns={[
                {title: '项目', dataIndex: 'field'},
                {title: '当前服务器政策', dataIndex: 'current'},
                {title: '完整草稿', dataIndex: 'draft'},
              ]}/>
            </>}

            {preview && !requiresRefresh && <Alert type={preview.proposed.limitedFields.length ? 'warning' : 'success'}
                                                   title={preview.proposed.limitedFields.length ? '草稿超出当前租户上限' : '草稿符合本次核验的租户上限'}
                                                   description={preview.proposed.limitedFields.length
                                                     ? preview.proposed.limitedFields.map(field => policyFieldNames[field]).join('、')
                                                     : `依据租户政策 v${preview.tenantPolicyVersion}、空间政策 v${preview.workspacePolicyVersion}、空间记录 v${preview.workspaceVersion}`}/>}
            <Flex gap={8} wrap>
              {kind === 'workspace' && <Button disabled={locked || requiresRefresh || !dirty} loading={previewing}
                                               onClick={() => void previewForm()}>预览草稿效果</Button>}
              <Button type="primary" htmlType="submit" loading={saving}
                      disabled={locked || requiresRefresh || !dirty || !!preview?.proposed.limitedFields.length}>
                {kind === 'tenant' ? '保存租户政策' : '保存空间政策'}
              </Button>
            </Flex>
          </Form>
        </Card>
      </>}
    </Flex>
  </section>;
}
