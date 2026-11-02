import React, {useEffect, useState} from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Flex,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Spin,
  Tag,
  Typography
} from 'antd';
import {type ModelCommand, type ModelFields, modelFieldsSchema} from '@/services/platform/model';
import {refKey} from '@/services/platform/ai';
import {type BrowserContext, storageKey} from './storage';
import {type ModelChange, useModelManagement} from './useModelManagement';
import {modelStatusText, validationText} from './modelManagementState';
import './personalModels.css';

interface Props {
  context: BrowserContext;
  onUpdated: (change: ModelChange) => void;
  onUnsafeChange?: (unsafe: boolean) => void;
  onBrowse?: () => void;
}

type Fields = Omit<ModelFields, 'connectionTemplate'> & { template: string };
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

/** 同一组件用于 ChatNew 右侧管理及独立页面；换账号／完整空间即卸载所有旧读取和临时密钥。 */
export default function PersonalModelManager(props: Props) {
  return <ModelManager key={storageKey(props.context, 'model-management')} {...props}/>;
}

function ModelManager({context, onUpdated, onUnsafeChange, onBrowse}: Props) {
  const vm = useModelManagement(context, onUpdated);
  const [form] = Form.useForm<Fields>();
  const [dirty, setDirty] = useState(false);
  const [name, setName] = useState('');
  const [token, setToken] = useState('');
  const [formError, setFormError] = useState('');
  const [plan, setPlan] = useState('');
  const detail = vm.detail;
  const unsafe = dirty || !!name || !!token || vm.hasTemporarySecret || !!vm.pending || vm.saving || vm.blocked;

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
      name: draft?.name ?? detail.model.name, template: draft ? refKey(draft.settings.connectionTemplate) : undefined,
      remoteModel: draft?.settings.remoteModel ?? '', contextWindowTokens: draft?.settings.contextWindowTokens ?? 8192,
      maxOutputTokens: draft?.settings.maxOutputTokens ?? 512
    });
    setDirty(false);
    setFormError('');
  }, [detail?.model.resourceId, detail?.draft.draft, form]);

  useEffect(() => {
    setPlan(detail?.options.length === 1 ? refKey(detail.options[0].templateBinding) : '');
  }, [detail?.options]);

  useEffect(() => {
    if (vm.closed) {
      setToken('');
      setName('');
      setDirty(false);
      form.resetFields();
    }
  }, [vm.closed]);

  const disabled = vm.closed || vm.saving || !!vm.pending || vm.blocked || vm.detailLoading;
  const located = {scope: context.scope, resourceId: detail?.model.resourceId ?? ''};
  const draft = detail?.draft.draft;
  const credential = detail?.draft.credential;
  const validation = detail?.validation;
  const canCredential = !!credential && !!detail?.actions.has('model:edit') && (credential.state === 'MISSING' || credential.mayMaintain);
  const validationMatches = !!draft && !!validation && validation.draftVersion === draft.version && validation.credentialVersion === credential?.version;
  const canPublish = validationMatches && validation?.state === 'SUCCEEDED' && !dirty;
  const canMove = !dirty && !token && !vm.hasTemporarySecret && !vm.pending && !vm.saving && !vm.blocked;

  async function submit(kind: ModelCommand['kind'], request: ModelCommand['request'], secret?: string) {
    setFormError('');
    const command = {kind, key: crypto.randomUUID(), request} as ModelCommand;
    if (kind === 'credential') setToken('');
    if (await vm.run(command, secret)) {
      setName('');
      setToken('');
      setDirty(false);
    }
  }

  async function save(values: Fields) {
    const template = detail?.templates.find(item => refKey(item.reference) === values.template);
    const parsed = modelFieldsSchema.safeParse({...values, connectionTemplate: template?.reference});
    if (!parsed.success) {
      setFormError('请填写名称、有效审核模板、远端模型和正确的容量；输出上限须小于上下文窗口。');
      return;
    }
    await submit('save', {...located, expectedVersion: draft?.version ?? 0, ...parsed.data});
  }

  return <section className="personal-models ai-management-panel" aria-label="个人模型管理">
    <Flex vertical gap={10} className="personal-models-intro">
      <Typography.Text strong>我可管理的模型</Typography.Text>
      <Typography.Paragraph
        type="secondary">当前管理空间中有读取资格的模型，包含草稿、停用和缺少预算项。个人密钥仅本人可用，租户凭据须另行授权用途。
      </Typography.Paragraph>
      {vm.closed && <Alert type="warning" title="账号会话已结束，请重新登录后打开模型管理。"/>}
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
      <Flex wrap gap={8}>
        <Button loading={vm.loading}
                disabled={!canMove || vm.closed}
                onClick={() => {
                  void vm.loadList();
                  if (vm.selected) void vm.open(vm.selected);
                }}>
          重新读取模型
        </Button>
        {onBrowse && <Button disabled={!canMove} onClick={onBrowse}>返回可用模型目录</Button>}
      </Flex>
    </Flex>
    <div className="personal-models-grid">
      <Flex vertical gap={10} className="personal-models-directory">
        <Card size="small" title="添加模型">
          <Flex vertical gap={8}>
            <Input aria-label="新模型名称" value={name} onChange={event => setName(event.target.value)} maxLength={100}
                   disabled={disabled || !vm.canCreate} placeholder="模型展示名称"/>
            <Button aria-label="创建私有模型" disabled={disabled || !vm.canCreate || !name.trim() || dirty || !!token}
                    loading={vm.saving}
                    onClick={() => void submit('create', {
                      scope: context.scope,
                      name: name.trim()
                    })}>创建私有模型</Button>
            {!vm.canCreate &&
              <Typography.Text type="secondary">当前创建资格未通过，不以空间角色代替动作授权。</Typography.Text>}
          </Flex>
        </Card>

        {!vm.records.length && !vm.loading &&
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前列表没有可读模型"/>}
        {vm.records.map(model =>
          <Card size="small" key={model.resourceId}
                className={model.resourceId === vm.selected ? 'personal-model-selected' : ''}>
            <Flex vertical gap={8}>
              <Typography.Text strong>{model.name}</Typography.Text>
              <Flex wrap gap={6}><Tag>{stateName[model.state]}</Tag><Typography.Text
                type="secondary">管理版本 {model.version}</Typography.Text></Flex>
              <Button disabled={!canMove} onClick={() => void vm.open(model.resourceId)}>管理 {model.name}</Button>
            </Flex>
          </Card>)}
        {vm.cursor && <Button loading={vm.loading} disabled={vm.loading}
                              onClick={() => void vm.loadList(true)}>继续查找可读模型</Button>}
      </Flex>

      <Flex vertical gap={12} className="personal-models-editor">
        {vm.detailLoading && <Flex gap={8} role="status"><Spin size="small"/>正在读取模型当前资格…</Flex>}
        {!detail && !vm.detailLoading &&
          <Typography.Paragraph>选择模型后可编辑草稿、维护本人凭据、验证连接和发布固定版本。</Typography.Paragraph>}
        {detail && <>
          <Card size="small" title={detail.model.name}>
            <Descriptions column={1} size="small" className="ai-management-info" items={[
              {key: 'state', label: '状态', children: stateName[detail.model.state]},
              {key: 'identity', label: '资源', children: detail.model.resourceId},
              {
                key: 'versions',
                label: '版本',
                children: `管理 ${detail.model.version} · 草稿 ${draft?.version ?? 0} · 凭据 ${credential?.version ?? 0}`
              },
            ]}/>
          </Card>

          <Card size="small" title="配置草稿">
            {!detail.actions.has('model:edit') &&
              <Typography.Paragraph>当前只有读取资格，不能编辑此草稿。</Typography.Paragraph>}
            {!detail.templates.length &&
              <Alert type="info" title="当前没有可用连接模板。请联系空间负责人确认个人凭据政策与部署审核配置。"/>}
            <Form<Fields> form={form} layout="vertical" className="ai-management-form" onFinish={save}
                          onValuesChange={() => setDirty(true)}
                          disabled={disabled || !detail.actions.has('model:edit')} autoComplete="off">
              <Form.Item name="name" label="展示名称" rules={[{required: true, whitespace: true, max: 100}]}><Input
                aria-label="模型展示名称" maxLength={100}/></Form.Item>
              <Form.Item name="template" label="审核连接模板" rules={[{required: true}]}><Select
                aria-label="审核连接模板" options={detail.templates.map(item => ({
                value: refKey(item.reference),
                label: `${item.providerId} · ${item.reference.id}@${item.reference.version}`
              }))}/></Form.Item>
              <Form.Item name="remoteModel" label="远端模型名"
                         rules={[{required: true, whitespace: true, max: 256}]}><Input aria-label="远端模型名"
                                                                                       maxLength={256}/></Form.Item>
              <Form.Item name="contextWindowTokens" label="上下文窗口（Token）" rules={[{required: true}]}><InputNumber
                aria-label="上下文窗口" min={2} max={10_000_000} precision={0}/></Form.Item>
              <Form.Item name="maxOutputTokens" label="输出上限（Token）" rules={[{required: true}]}><InputNumber
                aria-label="输出上限" min={1} max={1_000_000} precision={0}/></Form.Item>

              <Flex gap={8} wrap>
                <Button htmlType="submit"
                        type="primary"
                        disabled={disabled || !detail.actions.has('model:edit') || !detail.templates.length}>
                  保存模型草稿
                </Button>
                <Button disabled={vm.saving || !!vm.pending}
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

          <Card size="small" title={detail.model.ownershipType === 'TENANT' ? '租户凭据' : '个人凭据'}>
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

              <Popconfirm title="撤销本人密钥？旧发布将停止新调用，历史和费用记录保留。"
                          okText="确认撤销"
                          cancelText="取消"
                          onConfirm={async () => {
                            if (credential) await submit('revoke', {...located, expectedVersion: credential.version});
                          }}>
                <Button danger disabled={disabled || !credential?.mayMaintain || credential.state !== 'CONFIGURED'}>
                  撤销本人密钥
                </Button>
              </Popconfirm>

              <Typography.Text
                type="secondary">密钥不写入本地或会话存储。租户密钥须另行授权用途，模型共享不会转交个人密钥。替换后须重新验证并发布，旧代次不会自动升级。</Typography.Text>
            </Flex>
          </Card>

          <Card size="small" title="连接验证与发布">
            <Flex vertical gap={8}>
              <Typography.Text
                type="secondary">验证只检查认证后的模型列表，不生成内容，也不保证供应商所有能力。</Typography.Text>

              <Button
                disabled={disabled || dirty || !draft || !credential?.mayMaintain || credential.state !== 'CONFIGURED' || !detail.actions.has('model:validate')}
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
                <Alert type={validation.state === 'SUCCEEDED' && validationMatches ? 'success' : 'info'}
                       title={validationMatches ? validationText[validation.code] ?? '验证状态已更新，请检查当前结果。' : '此验证针对旧草稿或旧凭据，请重新验证。'}
                       description={`验证 ${validation.validationId} · ${validation.state}`}/>

                <Button disabled={vm.saving} onClick={() => void vm.queryValidation()}>查询原验证状态</Button>
              </>}

              <Typography.Text>审核发布方案</Typography.Text>

              <Select aria-label="审核发布方案" value={plan || undefined} onChange={setPlan}
                      disabled={disabled || !detail.actions.has('model:publish')}
                      options={detail.options.map(item => ({
                        value: refKey(item.templateBinding),
                        label: `${item.templateBinding.id}@${item.templateBinding.version} · ${item.rate.id}@${item.rate.version} · 目录 ${item.catalogRevision}`
                      }))}/>

              {!detail.options.length && <Typography.Text
                type="secondary">暂无匹配方案。先保存当前配置；如已保存，请由平台配置管理员在“审核模型与费率”登记相同连接和远端模型，并核对容量及个人发布开关，再重新读取模型。</Typography.Text>}

              <Button type="primary"
                      disabled={disabled || !canPublish || !plan || !detail.actions.has('model:publish')}
                      onClick={() => {
                        const option = detail.options.find(item => refKey(item.templateBinding) === plan);
                        if (option && draft && credential && validation) void submit('publish', {
                          ...located,
                          expectedVersion: detail.model.version,
                          draftVersion: draft.version,
                          credentialVersion: credential.version,
                          validationId: validation.validationId,
                          templateBinding: option.templateBinding,
                          catalogRevision: option.catalogRevision
                        });
                      }}>
                发布新的固定版本
              </Button>

              <Typography.Text
                type="secondary">发布采用十分钟内且依赖一致的成功验证。发布不分配预算，也不自动替换会话已应用的模型。</Typography.Text>
            </Flex>
          </Card>

          <Card size="small" title="发布版本与运行状态">
            <Flex vertical gap={8}>
              {!detail.versions.length && <Typography.Text type="secondary">尚无发布版本。</Typography.Text>}
              {detail.versions.map(item =>
                <Button key={refKey(item.binding)}
                        onClick={() => void vm.selectVersion(item)}>
                  {item.remoteOperation} ·查看版本 {item.binding.version}
                </Button>)}

              {detail.totalVersions > 10 && <Flex gap={8} wrap>
                <Button disabled={!canMove || detail.versionPage <= 1}
                        onClick={() => void vm.open(detail.model.resourceId, detail.versionPage - 1)}>上一页版本</Button>
                <Button disabled={!canMove || detail.versionPage * 10 >= detail.totalVersions}
                        onClick={() => void vm.open(detail.model.resourceId, detail.versionPage + 1)}>下一页版本</Button>
              </Flex>}

              {vm.selectedVersion && <Typography.Paragraph
                type="secondary">{vm.selectedVersion.source} · {vm.selectedVersion.binding.id}@{vm.selectedVersion.binding.version}<br/>
                {vm.selectedVersion.remoteOperation} · 上下文 {vm.selectedVersion.contextWindowTokens} ·
                费率 {vm.selectedVersion.rate.id}@{vm.selectedVersion.rate.version}
              </Typography.Paragraph>}

              {vm.selectedVersion?.source !== 'PERSONAL' && vm.selectedVersion &&
                <Typography.Text>此版本来自原平台目录，当前可用性请在聊天目录中重新核验。</Typography.Text>}

              {vm.statusError && <Alert type="warning" title={vm.statusError}/>}
              {vm.status && <Alert type={vm.status.ready ? 'success' : 'warning'}
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
    </div>
  </section>;
}
