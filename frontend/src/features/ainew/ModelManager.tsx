import {useConfigurationDraft, useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';
import React, {useEffect, useId, useMemo, useRef, useState} from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Spin,
  Steps,
  Tag,
  Typography
} from 'antd';
import {
  type ManagedModel,
  type ModelCommand,
  type ModelFields,
  modelFieldsSchema,
  type ModelListFilter,
  modelListFilterSchema
} from '@/services/platform/model';
import type {ModelUsageChecks} from '@/services/platform/modelUsage';
import type {ModelSelection} from '@/services/platform/ai';
import {refKey} from '@/services/platform/ai';
import {type BrowserContext, readValue, storageKey, writeValue} from './storage';
import {type ModelChange, useModelManagement} from './useModelManagement';
import {modelStatusText, validationText} from './modelManagementState';
import ModelSharingManager from './ModelSharingManager';
import {readSharingOperation} from './modelSharingRecovery';
import {subscribeAiConfiguration} from '@/features/workspace/aiConfigurationEvents';
import ModelUsagePreparationPanel from './ModelUsagePreparationPanel';
import './personalModels.css';
import MasterDetailLayout, {type MasterDetailItem} from '@/components/MasterDetailLayout';

interface Props {
  context: BrowserContext;
  disabled?: boolean;
  executionScope?: BrowserContext['scope'];
  initialModel?: ModelSelection;
  initialResourceId?: string;
  authorization?: boolean;
  onConfigureWorkspace?: (model: ModelSelection, scope?: BrowserContext['scope'], focus?: 'budget') => void;
  onUpdated: (change: ModelChange) => void;
  onUnsafeChange?: (unsafe: boolean) => void;
  onBrowse?: () => void;
  onUseInChat?: () => void;
  useInChatDisabled?: boolean;
  scopeFilter?: React.ReactNode;
  managementName?: string;
}

type Fields = Omit<ModelFields, 'planRef'> & { template: string };
export const modelAddAction = {key: 'add', label: '添加模型', icon: 'PlusOutlined'};
const stateName = {DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用'};
const commandName = {
  create: '创建模型',
  save: '保存草稿',
  credential: '保存凭据',
  revoke: '撤销凭据',
  validate: '连接验证',
  publish: '发布版本',
  disable: '停用模型'
};

/**
 * 构建模型管理列表项
 */
function buildModelItems(models: ManagedModel[], disabled: boolean): MasterDetailItem[] {
  return models.map(model => ({
    key: model.resourceId,
    label: `管理 ${model.name}`,
    disabled,
    content: <Flex vertical gap={8}>
      <Typography.Text strong>{model.name}</Typography.Text>
      <Flex wrap gap={6}>
        <Tag>{model.ownershipType === 'TENANT' ? '租户模型' : '个人模型'}</Tag>
        <Tag>{stateName[model.state]}</Tag>
      </Flex>
    </Flex>
  }));
}

/**
 * 模型管理组件
 * 同一组件用于聊天页右侧管理及独立配置页面。
 * 换账号/完整空间即卸载所有旧读取和临时密钥。
 */
export default function UnifiedModelManager(props: Props) {
  return <ModelManager
    key={JSON.stringify([storageKey(props.context, 'model-management'), props.initialModel, props.initialResourceId, props.executionScope])} {...props}/>;
}

function ModelManager({
                        context,
                        onUpdated,
                        onUnsafeChange,
                        onBrowse,
                        onUseInChat,
                        useInChatDisabled,
                        initialModel,
                        initialResourceId,
                        authorization,
                        onConfigureWorkspace,
                        disabled: blockedByOther = false,
                        executionScope, scopeFilter, managementName
                      }: Props) {
  const usageContext = useMemo(() => ({
    userId: context.userId,
    scope: executionScope ?? context.scope
  }), [context, executionScope]);
  const [sharingRecovery] = useState(() => readSharingOperation(context));
  const original = sharingRecovery.operation?.command;
  const sharingResource = original && original.kind !== 'create'
    ? ('target' in original.request ? original.request.target.resourceId : original.request.resourceId) : undefined;
  const filterKey = scopeFilter ? storageKey(context, 'model-management-list-filter') : undefined;
  const [initialFilter] = useState(() => filterKey
    ? modelListFilterSchema.safeParse(readValue(filterKey).value).data ?? {} : {});
  const vm = useModelManagement(context, onUpdated, sharingResource ?? initialModel?.resourceId ?? initialResourceId, initialModel, initialFilter);
  const [creating, setCreating] = useState(false);
  const [modelKind, setModelKind] = useState<'PERSONAL' | 'TENANT'>('PERSONAL');
  const [sharingUnsafe, setSharingUnsafe] = useState(false);
  const [stale, setStale] = useState(false);
  const [usageRevision, setUsageRevision] = useState(0);
  const [form] = Form.useForm<Fields>();
  const [dirty, setDirty] = useState(false);
  const [name, setName] = useState('');
  const [token, setToken] = useState('');
  const [formError, setFormError] = useState('');
  const [plan, setPlan] = useState('');
  const [usageUnsafe, setUsageUnsafe] = useState(false);
  const [usageChecks, setUsageChecks] = useState<ModelUsageChecks | null>(null);
  const [checkingPublication, setCheckingPublication] = useState(false);
  const checkingRef = useRef(false);
  const sectionId = useId();
  const detail = vm.detail;
  const unsafe = sharingUnsafe || usageUnsafe || dirty || !!name || !!token || vm.hasTemporarySecret || !!vm.pending || vm.saving || checkingPublication || vm.blocked;

  useEffect(() => subscribeAiConfiguration(usageContext.userId, usageContext.scope, () => {
    setStale(true);
    setUsageRevision(value => value + 1);
  }), [usageContext]);

  useEffect(() => {
    onUnsafeChange?.(unsafe);
  }, [unsafe, onUnsafeChange]);

  useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);

  useEffect(() => {
    const preventLoss = (event: BeforeUnloadEvent) => {
      if (unsafe) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', preventLoss);
    return () => window.removeEventListener('beforeunload', preventLoss);
  }, [unsafe]);

  useEffect(() => {
    if (!detail) return;
    const draft = detail.draft.draft;
    form.setFieldsValue({
      name: draft?.name ?? detail.model.name, template: draft ? refKey(draft.settings.planRef) : undefined,
      contextWindowTokens: draft?.settings.contextWindowTokens ?? 8192,
      maxOutputTokens: draft?.settings.maxOutputTokens ?? 512
    });
    setDirty(false);
    setFormError('');
  }, [detail?.model.resourceId, detail?.draft.draft, form]);

  useEffect(() => {
    setPlan(detail?.draft.draft ? refKey(detail.draft.draft.settings.planRef) : '');
  }, [detail?.options]);

  useEffect(() => {
    if (vm.closed) {
      setToken('');
      setName('');
      setDirty(false);
      form.resetFields();
    }
  }, [vm.closed]);

  const disabled = checkingPublication || blockedByOther || sharingUnsafe || usageUnsafe || vm.closed || vm.saving || !!vm.pending || vm.blocked || vm.detailLoading;
  const navigation = useConfigurationNavigation();
  const located = {scope: context.scope, resourceId: detail?.model.resourceId ?? ''};
  const draft = detail?.draft.draft;
  const credential = detail?.draft.credential;
  const validation = detail?.validation;
  const canCredential = !!credential && !!detail?.actions.has('model:edit') && (credential.state === 'MISSING' || credential.mayMaintain);
  const validationMatches = !!draft && !!validation && validation.draftVersion === draft.version && validation.credentialVersion === credential?.version;
  const deadline = validation?.publishableUntil ? Date.parse(validation.publishableUntil) : undefined;
  const [expiredDeadline, setExpiredDeadline] = useState<number>();
  useEffect(() => {
    if (deadline === undefined || !Number.isFinite(deadline)) return;
    const timer = window.setTimeout(() => setExpiredDeadline(deadline), Math.max(0, deadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [deadline]);
  const validationExpired = deadline !== undefined && (expiredDeadline === deadline || !(Date.now() < deadline));
  const validationPassed = validationMatches && validation?.state === 'SUCCEEDED' && !validationExpired && credential?.state === 'CONFIGURED';
  const canValidate = !!draft && !!credential?.mayMaintain && credential.state === 'CONFIGURED' && !!detail?.actions.has('model:validate');
  const publicationOption = detail?.options.find(item => refKey(item.planRef) === plan);
  const validationPending = validationMatches && (validation?.state === 'PENDING' || validation?.state === 'RUNNING');
  const canPublish = validationPassed && !dirty && !!publicationOption && !!detail?.actions.has('model:publish');
  const version = vm.selectedVersion;
  const currentChecks = version && usageChecks?.resource.resourceId === version.resourceId
  && refKey(usageChecks.binding) === refKey(version.binding)
  && usageChecks.scope.workspaceId === usageContext.scope.workspaceId
  && usageChecks.scope.tenantId === usageContext.scope.tenantId ? usageChecks : null;
  const allConditions = [...(currentChecks?.spaceConditions ?? []), ...(currentChecks?.personalConditions ?? [])];
  const usagePassed = !!currentChecks && allConditions.every(check => check.status === 'PASS');
  const usageBlocked = allConditions.some(check => check.status === 'BLOCKED');
  const sameDraftPublished = detail?.model.state === 'PUBLISHED' && detail.draft.currentPublicationMatchesDraft !== false;
  const next = dirty || (!draft && !sameDraftPublished) ? 'config' : credential?.state !== 'CONFIGURED' && (!sameDraftPublished || !!draft) ? 'key'
    : !sameDraftPublished ? 'release' : 'usage';
  const nextAction = next === 'config'
    ? detail?.actions.has('model:edit') && !!detail.options.length ? '前往填写或保存配置' : null
    : next === 'key' ? canCredential ? '前往维护密钥' : null
      : next === 'release' ? canPublish ? '前往发布版本' : !validationPassed && canValidate
          ? validationPending ? '前往查看原验证' : '前往连接验证' : null
        : version ? '查看使用条件' : detail?.versions.length ? '前往选择固定版本' : null;

  function goToSection(section: string) {
    const node = document.getElementById(`${sectionId}-${section}`);
    node?.focus({preventScroll: true});
    node?.scrollIntoView?.({block: 'start'});
  }

  const canMove = !blockedByOther && !sharingUnsafe && !usageUnsafe && !name && !dirty && !token && !vm.hasTemporarySecret && !vm.pending && !vm.saving && !checkingPublication && !vm.blocked;

  useConfigurationDraft({
    group: 'models', label: token || vm.hasTemporarySecret ? '模型临时密钥输入' : '模型配置草稿',
    status: vm.blocked ? 'blocked' : vm.saving || checkingPublication ? 'submitting' : vm.pending ? 'pending'
      : dirty || !!name || !!token || vm.hasTemporarySecret ? 'dirty' : 'clean',
    save: !name && !token && !vm.hasTemporarySecret && !creating ? async () => {
      try {
        return await save(await form.validateFields());
      } catch {
        return false;
      }
    } : undefined,
    discard: async () => {
      setName('');
      setToken('');
      vm.clearSecret();
      setDirty(false);
      setCreating(false);
      if (detail) await vm.open(detail.model.resourceId);
      return true;
    }
  });
  const move = (action: () => void) => {
    if (navigation.available) navigation.request(action, ['models']);
    else if (canMove) action();
  };

  async function submit(kind: ModelCommand['kind'], request: ModelCommand['request'], secret?: string) {
    setFormError('');
    const command = {kind, key: crypto.randomUUID(), request} as ModelCommand;
    if (kind === 'credential') setToken('');
    if (await vm.run(command, secret)) {
      setName('');
      setToken('');
      setDirty(false);
      if (kind === 'create') setCreating(false);
      return true;
    }
    return false;
  }

  async function publish() {
    if (disabled || !canPublish || checkingRef.current || !detail || !draft || !credential || !validation || !publicationOption) return;
    checkingRef.current = true;
    setCheckingPublication(true);
    setFormError('');
    try {
      const current = await vm.queryValidation();
      if (!current || current.state !== 'SUCCEEDED' || current.draftVersion !== draft.version
        || current.credentialVersion !== credential.version
        || (current.publishableUntil && !(Date.now() < Date.parse(current.publishableUntil)))) return;
      await submit('publish', {
        ...located,
        expectedVersion: detail.model.version,
        draftVersion: draft.version,
        credentialVersion: credential.version,
        validationId: current.validationId,
        planRef: publicationOption.planRef,
        catalogRevision: publicationOption.catalogRevision
      });
    } finally {
      checkingRef.current = false;
      setCheckingPublication(false);
    }
  }

  async function save(values: Fields) {
    const template = detail?.options.find(item => refKey(item.planRef) === values.template);
    const parsed = modelFieldsSchema.safeParse({...values, planRef: template?.planRef});
    if (!parsed.success) {
      setFormError('请填写名称、有效审核方案和正确的容量；输出上限须小于上下文窗口。');
      return false;
    }
    const {name: displayName, ...settings} = parsed.data;
    return submit('save', {...located, expectedVersion: draft?.version ?? 0, name: displayName, settings});
  }

  return <MasterDetailLayout
    label="模型管理" style={onBrowse ? {height: '75dvh'} : undefined}
    actions={[
      ...(!scopeFilter ? [{
        key: 'refresh',
        label: '重新读取模型',
        icon: 'ReloadOutlined',
        loading: vm.loading,
        disabled: !canMove || vm.closed,
        onClick: () => {
          void vm.loadList();
          if (vm.selected) void vm.open(vm.selected);
        }
      }] : []),
      {
        ...modelAddAction,
        disabled: (!navigation.available && !canMove) || !vm.canCreate,
        onClick: () => move(() => {
          setCreating(true);
          setName('');
          setModelKind(vm.creationKinds[0] ?? 'PERSONAL');
        })
      }
    ]}
    selectedKey={creating ? undefined : vm.selected ?? undefined}
    onSelect={key => move(() => {
      setCreating(false);
      void vm.open(key);
    })}
    listHeader={scopeFilter && <ModelListControls scopeFilter={scopeFilter} onFilter={vm.setListFilter}
                                                  initialFilter={initialFilter} filterKey={filterKey}/>}
    emptyText={vm.loading ? '正在读取模型列表…' : vm.cursor ? '本批没有匹配模型，可以继续加载。' : '没有匹配的可读模型。'}
    listFooter={vm.cursor && <Button loading={vm.loading} disabled={vm.loading}
                                     onClick={() => void vm.loadList(true)}>继续查找可读模型</Button>}
    items={buildModelItems(vm.records, !navigation.available && !canMove)}>
    {/* 右侧模型表单详情 */}
    <div className="personal-models ai-management-panel">
      <Flex vertical gap={10} className="personal-models-intro">
        {stale && <Alert type="info" title="空间 AI 配置已变化，请重新读取准备状态和可用目录；当前草稿及选择保持不变。"/>}
        {vm.closed && <Alert type="warning" title="账号会话已结束，请重新登录后打开模型管理。"/>}
        {vm.listError && <Alert type="warning" title={vm.listError}/>}
        {vm.error && <Alert type="warning" showIcon title={vm.error}/>}
        {formError && <Alert type="warning" showIcon title={formError}/>}
        {vm.notice && <Alert type="success" showIcon title={vm.notice}/>}
        {vm.blocked && <Alert type="warning" title="恢复记录不可用，暂不能创建新操作。请保留本页并检查浏览器存储。"/>}
        {vm.pending && <Card size="small" title={`待确认：${commandName[vm.pending.kind]}`}>
          <Flex vertical gap={8}>
            <Typography.Paragraph>按原参数和原操作键确认；不会自动换键或重复创建。刷新后也请先处理这项操作。</Typography.Paragraph>
            {vm.pending.kind === 'credential' &&
              <Typography.Text>密钥没有写入浏览器存储。重新打开页面后，须填写原操作的同一密钥。</Typography.Text>}
            {vm.pending.kind === 'credential' && !vm.hasTemporarySecret &&
              <Input.Password aria-label="原操作密钥" value={token}
                              onChange={event => setToken(event.target.value)} visibilityToggle={false}
                              autoComplete="new-password" maxLength={8192}/>}
            <Button aria-label="重试确认原操作" loading={vm.saving}
                    disabled={vm.closed || vm.pending.kind === 'credential' && !vm.hasTemporarySecret && !token}
                    onClick={() => {
                      const value = token;
                      setToken('');
                      void vm.retry(value || undefined);
                    }}>重试确认原操作</Button>
          </Flex>
        </Card>}

        {(token || vm.hasTemporarySecret) && <Button onClick={() => {
          setToken('');
          vm.clearSecret();
        }}>清除临时密钥输入
        </Button>}

        {dirty && <Alert type="info" title="配置有未保存修改；保存或放弃后再切换模型、空间。"/>}
        {onBrowse && <Button disabled={!canMove} onClick={onBrowse}>返回可用模型目录</Button>}
      </Flex>

      {creating ? <>
        <Card size="small" title="添加模型">
          <Typography.Paragraph>管理空间：{managementName ?? context.scope.workspaceId}</Typography.Paragraph>
          <Flex vertical gap={8}>
            <Select aria-label="模型归属" value={modelKind} disabled={disabled || dirty || !!name || !!token}
                    options={vm.creationKinds.map(value => ({
                      value,
                      label: value === 'PERSONAL' ? '个人模型' : '租户模型'
                    }))}
                    onChange={setModelKind}/>
            <Typography.Text type="secondary">{modelKind === 'PERSONAL'
              ? '个人模型使用本人密钥；管理空间固定，发布后还需配置执行空间。'
              : '模型归租户，当前空间用于维护；创建还需租户治理及模型创建资格，凭据用途须独立授权。'}</Typography.Text>
            <Input aria-label="新模型名称" value={name} onChange={event => setName(event.target.value)} maxLength={100}
                   disabled={disabled || !vm.canCreate} placeholder="模型展示名称"/>
            <Button aria-label="创建私有模型"
                    disabled={disabled || !vm.creationKinds.includes(modelKind) || !name.trim() || dirty || !!token}
                    loading={vm.saving}
                    onClick={() => void submit('create', {
                      scope: context.scope,
                      name: name.trim(), modelKind
                    })}>{modelKind === 'PERSONAL' ? '创建个人模型' : '创建租户模型'}</Button>
            {!vm.canCreate &&
              <Typography.Text type="secondary">当前创建资格未通过，不以空间角色代替动作授权。</Typography.Text>}
          </Flex>
          <Button disabled={vm.saving || !!vm.pending || vm.blocked} onClick={() => {
            setName('');
            setCreating(false);
          }}>取消添加模型</Button>
        </Card>
      </> : <>
        <Flex vertical gap={12} className="personal-models-editor">
          {vm.detailLoading && <Flex gap={8} role="status"><Spin size="small"/>正在读取模型当前资格…</Flex>}
          {!detail && !vm.detailLoading &&
            <Typography.Paragraph>1. 点击左侧模型以编辑，或查看详情；<br/>2.
              点击左上方新增图标，以创建新模型。</Typography.Paragraph>}
          {detail && <>
            <Card size="small" title={detail.model.name}>
              <Descriptions column={1} size="small" className="ai-management-info" items={[
                {
                  key: 'ownership',
                  label: '归属',
                  children: detail.model.ownershipType === 'TENANT' ? '租户模型' : '个人模型'
                },
                {key: 'management', label: '管理空间', children: managementName ?? context.scope.workspaceId},
                ...(usageContext.scope.workspaceId !== context.scope.workspaceId ? [{
                  key: 'execution', label: '使用检查的执行空间', children: usageContext.scope.workspaceId,
                }] : []),
                {key: 'state', label: '状态', children: stateName[detail.model.state]},
              ]}/>
              <details>
                <summary>技术详情</summary>
                <Typography.Text type="secondary">资源 {detail.model.resourceId} · 管理版本 {detail.model.version} ·
                  草稿版本 {draft?.version ?? '无'} · 密钥代次 {credential?.version ?? '无'}</Typography.Text>
              </details>
            </Card>

            <Card size="small" title="模型维护进度">
              <Flex vertical gap={12}>
                <Steps className="model-maintenance-steps" size="small"
                       current={['config', 'key', 'release', 'usage'].indexOf(next)} items={[
                  {
                    title: '基本配置',
                    content: dirty ? '有未保存修改' : draft ? '草稿已保存' : '尚未保存草稿',
                    status: draft && !dirty ? 'finish' : undefined
                  },
                  {
                    title: '密钥',
                    content: credential?.state === 'CONFIGURED' ? '已配置' : credential?.state === 'REVOKED' ? '已撤销' : '尚未配置',
                    status: credential?.state === 'CONFIGURED' ? 'finish' : undefined
                  },
                  {
                    title: '验证发布', content: sameDraftPublished ? '当前配置已有发布版本'
                      : validationPassed ? '验证通过，等待发布' : validationExpired || validation?.state === 'STALE' || validation && !validationMatches
                        ? '验证已失效' : validation?.state === 'FAILED' ? '验证未通过'
                          : validation ? '等待验证结果' : '需要验证当前配置',
                    status: sameDraftPublished ? 'finish' : validationMatches && validation?.state === 'FAILED' ? 'error' : undefined
                  },
                  {
                    title: '使用条件',
                    content: !version ? '尚未选择固定版本' : usagePassed ? '当前检查全部通过' : usageBlocked ? '存在待处理项' : '有条件未核验',
                    status: usagePassed ? 'finish' : undefined
                  },
                ]}/>
                {nextAction ? <Button type="primary" disabled={disabled}
                                      onClick={() => goToSection(next === 'usage' && !version ? 'versions' : next)}>
                  {nextAction}
                </Button> : <Typography.Text type="secondary">
                  {next === 'config' ? detail.actions.has('model:edit') ? '请联系平台配置管理员核对审核模型方案。' : '请联系模型维护者保存配置。' : next === 'key' ? '请联系密钥责任人维护密钥。'
                    : next === 'release' ? validationPassed
                      ? detail.actions.has('model:publish') ? '请联系平台配置管理员核对审核发布方案。' : '验证已通过，请联系模型发布负责人。'
                      : '请联系模型维护者验证并发布。' : '当前没有可供检查的版本。'}
                </Typography.Text>}
                {next !== 'usage' && detail.model.state === 'PUBLISHED' && detail.versions.length > 0 &&
                  <Button type="link" onClick={() => goToSection('versions')}>查看已有发布版本</Button>}
              </Flex>
            </Card>

            <Card id={`${sectionId}-config`} role="region" tabIndex={-1} aria-label="基本配置"
                  size="small" title="配置草稿">
              {!detail.actions.has('model:edit') &&
                <Typography.Paragraph>当前只有读取资格，不能编辑此草稿。</Typography.Paragraph>}
              {!detail.options.length &&
                <Alert type="info" title="当前没有可采用审核方案，请确认当前模型政策与平台审核配置。"/>}
              <Form<Fields> form={form} layout="vertical" className="ai-management-form" onFinish={save}
                            onValuesChange={() => setDirty(true)}
                            disabled={disabled || !detail.actions.has('model:edit')} autoComplete="off">
                <Form.Item name="name" label="展示名称" rules={[{required: true, whitespace: true, max: 100}]}><Input
                  aria-label="模型展示名称" maxLength={100}/></Form.Item>
                <Form.Item name="template" label="审核模型方案" rules={[{required: true}]}><Select
                  aria-label="审核模型方案" options={detail.options.map(item => ({
                  value: refKey(item.planRef),
                  label: item.displayName
                }))}/></Form.Item>
                {draft && <details>
                  <summary>审核方案技术详情</summary>
                  <Typography.Text type="secondary">{draft.plan.planRef.id}@{draft.plan.planRef.version} ·
                    方案模型 {draft.plan.remoteModel} ·
                    费率 {draft.plan.rate.id}@{draft.plan.rate.version}</Typography.Text>
                </details>}
                <Form.Item name="contextWindowTokens" label="上下文窗口（Token）" rules={[{required: true}]}><InputNumber
                  aria-label="上下文窗口" min={2} max={10_000_000} precision={0}/></Form.Item>
                <Form.Item name="maxOutputTokens" label="输出上限（Token）" rules={[{required: true}]}><InputNumber
                  aria-label="输出上限" min={1} max={1_000_000} precision={0}/></Form.Item>

                <Flex gap={8} wrap>
                  <Button htmlType="submit"
                          type={detail.actions.has('model:edit') && detail.options.length ? 'primary' : 'default'}
                          disabled={disabled || !detail.actions.has('model:edit') || !detail.options.length}>
                    保存模型草稿
                  </Button>
                  <Button disabled={usageUnsafe || checkingPublication || vm.saving || !!vm.pending}
                          onClick={() => {
                            setDirty(false);
                            setToken('');
                            vm.clearSecret();
                            void vm.open(detail.model.resourceId);
                          }}>
                    放弃修改并读取当前内容
                  </Button>
                </Flex>
              </Form>

              <Typography.Paragraph
                type="secondary">保存只修改草稿，不改变已发布版本。远端模型及费率还须匹配平台审核方案。</Typography.Paragraph>
            </Card>

            <Card id={`${sectionId}-key`} role="region" tabIndex={-1} aria-label="密钥"
                  size="small" title={detail.model.ownershipType === 'TENANT' ? '租户凭据' : '个人凭据'}>
              <Flex vertical gap={8}>
                <Typography.Text>
                  {credential?.state === 'MISSING' ? '尚未配置密钥' : credential?.state === 'REVOKED' ? '密钥已撤销' : '密钥已配置，不能读取原值'}
                </Typography.Text>

                {!canCredential && <Typography.Text
                  type="secondary">当前不能维护此密钥。编辑或使用模型不自动转交凭据责任。</Typography.Text>}

                <Input.Password aria-label={detail.model.ownershipType === 'TENANT' ? '租户 API 密钥' : '个人 API 密钥'}
                                value={token}
                                onChange={event => setToken(event.target.value)}
                                disabled={disabled || !canCredential || !draft}
                                visibilityToggle={false} autoComplete="new-password"
                                maxLength={8192}
                                placeholder={detail.model.ownershipType === 'TENANT' ? '仅输入有权提供给该租户使用的密钥' : '仅输入本人提供的密钥'}/>

                <Button disabled={disabled || dirty || !draft || !canCredential || !token}
                        onClick={() => {
                          if (credential) void submit('credential', {
                            ...located,
                            expectedVersion: credential.version,
                            ...(detail.model.ownershipType === 'TENANT' ? {credentialKind: 'TENANT' as const} : {}),
                          }, token);
                        }}>
                  {detail.model.ownershipType === 'TENANT' ? '保存或替换租户密钥' : '保存或替换本人密钥'}
                </Button>

                <Popconfirm
                  title={`撤销${detail.model.ownershipType === 'TENANT' ? '租户' : '本人'}密钥？旧发布将停止新调用，历史和费用记录保留。`}
                  okText="确认撤销"
                  cancelText="取消"
                  onConfirm={async () => {
                    if (credential) await submit('revoke', {...located, expectedVersion: credential.version});
                  }}>
                  <Button danger disabled={disabled || !credential?.mayMaintain || credential.state !== 'CONFIGURED'}>
                    {detail.model.ownershipType === 'TENANT' ? '撤销租户密钥' : '撤销本人密钥'}
                  </Button>
                </Popconfirm>

                <Typography.Text
                  type="secondary">密钥不写入本地或会话存储。租户密钥须另行授权用途，模型共享不会转交个人密钥。替换后须重新验证并发布，旧代次不会自动升级。</Typography.Text>
              </Flex>
            </Card>

            <Card id={`${sectionId}-release`} role="region" tabIndex={-1} aria-label="验证发布"
                  size="small" title="连接验证与发布">
              <Flex vertical gap={8}>
                <Typography.Text
                  type="secondary">验证只检查认证后的模型列表，不生成内容，也不保证供应商所有能力。</Typography.Text>

                <Button
                  disabled={disabled || dirty || !canValidate || validationPending}
                  onClick={() => {
                    if (draft && credential) void submit('validate', {
                      ...located,
                      draftVersion: draft.version,
                      credentialVersion: credential.version
                    });
                  }}>
                  验证当前连接
                </Button>

                {validation && <>
                  <Alert
                    type={validationPassed ? 'success' : validation.state === 'FAILED' || validationExpired ? 'warning' : 'info'}
                    title={!validationMatches ? '此验证针对旧草稿或旧凭据，请重新验证。' : validationExpired ? validationText.VALIDATION_EXPIRED
                      : validationText[validation.code] ?? '验证状态已更新，请检查当前结果。'}/>
                  <details>
                    <summary>验证技术详情</summary>
                    验证 {validation.validationId} · {validation.state}</details>
                  {detail.actions.has('model:validate') &&
                    <Button disabled={vm.saving || checkingPublication}
                            onClick={() => void vm.queryValidation()}>查询原验证状态</Button>}
                </>}

                <Typography.Text>审核发布方案</Typography.Text>

                <Select aria-label="审核发布方案" value={plan || undefined} onChange={setPlan}
                        disabled
                        options={detail.options.map(item => ({
                          value: refKey(item.planRef),
                          label: item.displayName
                        }))}/>

                {!detail.options.length && <Typography.Text
                  type="secondary">暂无可采用方案，请联系平台配置管理员发布审核方案后重新读取。</Typography.Text>}

                {detail.actions.has('model:publish') &&
                  <Button aria-label="发布新的固定版本" type={canPublish ? 'primary' : 'default'}
                          disabled={disabled || !canPublish} loading={checkingPublication}
                          onClick={() => void publish()}>
                  发布新的固定版本
                  </Button>}
                {!detail.actions.has('model:publish') &&
                  <Typography.Text type="secondary">当前没有发布权限，请联系模型发布负责人。</Typography.Text>}

                <Typography.Text
                  type="secondary">发布采用十分钟内且依赖一致的成功验证。发布不分配预算，也不自动替换会话已应用的模型。</Typography.Text>
              </Flex>
            </Card>

            <Card id={`${sectionId}-versions`} role="region" tabIndex={-1} aria-label="发布版本"
                  size="small" title="发布版本与依赖状态">
              <Flex vertical gap={8}>
                {!detail.versions.length && <Typography.Text type="secondary">尚无发布版本。</Typography.Text>}
                {detail.versions.map(item =>
                  <Button key={refKey(item.binding)} disabled={!canMove}
                          aria-pressed={!!version && refKey(item.binding) === refKey(version.binding)}
                          onClick={() => void vm.selectVersion(item)}>
                    {item.remoteOperation} ·查看版本 {item.binding.version}
                  </Button>)}

                {detail.totalVersions > 10 && <Flex gap={8} wrap>
                  <Button disabled={!canMove || detail.versionPage <= 1}
                          onClick={() => void vm.open(detail.model.resourceId, detail.versionPage - 1)}>上一页版本</Button>
                  <Button disabled={!canMove || detail.versionPage * 10 >= detail.totalVersions}
                          onClick={() => void vm.open(detail.model.resourceId, detail.versionPage + 1)}>下一页版本</Button>
                </Flex>}

                {version && onConfigureWorkspace && <Button disabled={!canMove || useInChatDisabled}
                                                            onClick={() => onConfigureWorkspace({
                                                              resourceId: version.resourceId,
                                                              binding: version.binding
                                                            })}>
                  选择执行空间
                </Button>}
                {version && <Typography.Text>对应费率：{version.rate.id}@{version.rate.version}</Typography.Text>}
                {vm.selectedVersion && <details>
                  <summary>所选版本技术详情</summary>
                  <Typography.Paragraph type="secondary">
                    {vm.selectedVersion.source} · {vm.selectedVersion.binding.id}@{vm.selectedVersion.binding.version}<br/>
                    {vm.selectedVersion.remoteOperation} · 上下文 {vm.selectedVersion.contextWindowTokens}
                  </Typography.Paragraph>
                </details>}

                {vm.selectedVersion?.source !== 'RESOURCE' && vm.selectedVersion &&
                  <Typography.Text>此版本来自原平台目录，当前可用性请在聊天目录中重新核验。</Typography.Text>}

                {vm.statusError && <Alert type="warning" title={vm.statusError}/>}
                {vm.status && <Alert type={vm.status.valid ? 'success' : 'warning'}
                                     title={modelStatusText[vm.status.reason] ?? '当前尚未就绪，请重新检查权限与发布依赖。'}/>}
                {vm.status && <Button
                  onClick={() => vm.selectedVersion && void vm.selectVersion(vm.selectedVersion)}>
                  重新检查此版本
                </Button>}

                <Popconfirm title="停用此模型的全部版本？将阻止新调用，保留历史记录。"
                            okText="确认停用"
                            cancelText="取消"
                            onConfirm={() => submit('disable', {...located, expectedVersion: detail.model.version})}>

                  <Button danger
                          disabled={disabled || dirty || detail.model.state !== 'PUBLISHED' || !detail.actions.has('model:disable')}>
                    停用模型全部版本
                  </Button>
                </Popconfirm>
              </Flex>
            </Card>
          </>}
        </Flex>
        {/* 授权恢复操作可能尚未读到模型详情，仍保留原操作的确认入口。 */}
        {(detail || sharingRecovery.operation || sharingRecovery.blocked) &&
          <Card size="small" title={authorization ? '模型授权（来源入口）' : '模型授权与使用范围'}>
            <ModelSharingManager context={context} embedded model={detail?.model}
                                 disabled={blockedByOther || usageUnsafe || dirty || !!name || !!token || vm.hasTemporarySecret || !!vm.pending || vm.saving || vm.blocked || vm.closed || vm.detailLoading}
                                 onCreated={resourceId => void vm.open(resourceId)} onUnsafeChange={setSharingUnsafe}/>
          </Card>}
        <section className="model-usage-section" id={`${sectionId}-usage`} tabIndex={-1} aria-label="使用条件">
          {detail && !version && <Card size="small" title="使用条件">
            请先在发布版本中选择一个固定版本。检查将在执行空间 {usageContext.scope.workspaceId} 中进行。
          </Card>}
          <ModelUsagePreparationPanel
            key={storageKey(usageContext, 'usage-target')} context={usageContext}
            refreshRevision={usageRevision}
            onChecks={setUsageChecks}
            onConfigureWorkspace={onConfigureWorkspace && (model => onConfigureWorkspace(model, usageContext.scope))}
            onConfigureBudget={onConfigureWorkspace && (model => onConfigureWorkspace(model, usageContext.scope, 'budget'))}
            target={vm.selectedVersion ? {
              resourceId: vm.selectedVersion.resourceId,
              binding: {...vm.selectedVersion.binding, type: 'binding'}
            } : null}
            disabled={checkingPublication || blockedByOther || dirty || !!name || !!token || vm.hasTemporarySecret || vm.saving || !!vm.pending || vm.blocked || vm.closed || vm.detailLoading}
            navigationDisabled={useInChatDisabled}
            onUnsafeChange={setUsageUnsafe}
            onPrepared={() => {
              onUpdated({
                kind: 'prepare',
                resourceId: vm.selectedVersion?.resourceId ?? vm.selected
              });
              if (vm.selectedVersion) void vm.selectVersion(vm.selectedVersion);
            }} onUseInChat={onUseInChat}/>
        </section>
      </>}
    </div>
  </MasterDetailLayout>;
}

export function ModelListControls({scopeFilter, onFilter, initialFilter = {}, filterKey}: {
  scopeFilter: React.ReactNode;
  onFilter?: (query: ModelListFilter) => void;
  initialFilter?: ModelListFilter;
  filterKey?: string;
}) {
  const [search, setSearch] = useState(initialFilter.search ?? '');
  const [query, setQuery] = useState(initialFilter);
  const applied = useRef(initialFilter);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const composing = useRef(false);
  useEffect(() => () => clearTimeout(searchTimer.current), []);

  function updateFilter(change: ModelListFilter, debounce = false) {
    clearTimeout(searchTimer.current);
    if (!onFilter) return;
    const previous = applied.current;
    const next = {...previous, ...change};
    if (filterKey) writeValue(filterKey, next);
    if (next.search === previous.search && next.modelKind === previous.modelKind && next.state === previous.state) return;
    const apply = () => {
      applied.current = next;
      setQuery(next);
      onFilter(next);
    };
    if (debounce) searchTimer.current = setTimeout(apply, 250);
    else apply();
  }

  return <div className="model-list-filters">
    {scopeFilter}
    <Input type="search" aria-label="搜索模型名称" placeholder="搜索模型名称" maxLength={100} allowClear
           disabled={!onFilter} value={search}
           onChange={event => {
             const value = event.target.value;
             setSearch(value);
             if (!composing.current) updateFilter({search: value.trim() || undefined}, true);
           }}
           onCompositionStart={() => {
             composing.current = true;
             clearTimeout(searchTimer.current);
           }}
           onCompositionEnd={event => {
             composing.current = false;
             updateFilter({search: event.currentTarget.value.trim() || undefined}, true);
           }}
           onPressEnter={event => updateFilter({search: event.currentTarget.value.trim() || undefined})}/>
    <Select aria-label="筛选模型归属" placeholder="全部归属" allowClear disabled={!onFilter} value={query.modelKind}
            options={[{label: '个人模型', value: 'PERSONAL'}, {label: '租户模型', value: 'TENANT'}]}
            onChange={modelKind => updateFilter({modelKind, search: search.trim() || undefined})}/>
    <Select aria-label="筛选模型状态" placeholder="全部状态" allowClear disabled={!onFilter} value={query.state}
            options={Object.entries(stateName).map(([value, label]) => ({value, label}))}
            onChange={state => updateFilter({state, search: search.trim() || undefined})}/>
  </div>;
}
