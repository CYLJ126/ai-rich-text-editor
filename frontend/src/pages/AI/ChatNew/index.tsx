import {useConfigurationDiscovery} from '@/features/ainew/useConfigurationSelection';
import React, {useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {useIntl, useModel} from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Result,
  Spin,
  Tabs,
  Tag,
  Typography
} from 'antd';
import {LockOutlined} from '@ant-design/icons';
import {aiReadAccessIssue} from '@/features/ainew/accessFeedback';
import type {AiScope, ChatModelOption} from '@/services/arte-ai';
import {AiApiError} from '@/services/arte-ai';
import messages from '@/locales/zh-CN/aiChat';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import WorkspaceQuickSelect from '@/features/workspace/WorkspaceQuickSelect';
import WorkspaceAiPreparation from '@/features/ainew/WorkspaceAiPreparation';
import WorkspaceAiSettings from '@/features/ainew/WorkspaceAiSettings';
import {registerWorkspaceGuard, useCurrentWorkspace} from '@/features/workspace/useWorkspace';
import type {WorkspaceView} from '@/services/platform/workspace';
import ChatConfigurationForm, {type ConfigurationDraft} from '@/features/ainew/ChatConfigurationForm';
import BusinessChatPanel, {type ChatInteractionState} from '@/features/ainew/BusinessChatPanel';
import {chatConfigSchema, chatScopeSchema, type ChatTestConfig, modelOptionKey} from '@/features/ainew/config';
import {useConversationDirectory} from '@/features/ainew/useConversationDirectory';
import ConversationDirectorySidebar from '@/features/ainew/ConversationDirectorySidebar';
import ConversationDirectoryHeader from '@/features/ainew/ConversationDirectoryHeader';
import ChatWorkspaceLayout from '@/features/ainew/ChatWorkspaceLayout';
import {useChatLayout} from '@/features/ainew/useChatLayout';
import {useComponentHeight} from '@/utils/useDynamicHeight';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import {type ConfigurationRejected} from '@/features/ainew/configurationInvalidation';
import {
  type BrowserContext,
  clearAccountSession,
  readValue,
  removeValue,
  sameScope,
  storageKey,
  writeValue
} from '@/features/ainew/storage';
import './style.css';

function WorkspaceChat({
                         userId,
                         workspace,
                         t,
                         translate,
                         protectRef,
                         onChooseWorkspace,
                         workspaceManager,
                         workspaceRevision,
                         onConnect,
                         onPlatformConfig
                       }: {
  userId: string;
  workspaceManager: React.ReactNode;
  workspaceRevision: number;
  onChooseWorkspace: () => void;
  onConnect: () => void; onPlatformConfig: () => void;
  workspace: Pick<WorkspaceView, 'scope' | 'name'> & { state: WorkspaceView['state'] | null };
  t: (key: string) => string;
  translate: (id: string) => string;
  protectRef: React.RefObject<((proceed: () => void) => void) | null>;
}) {
  const context = useMemo<BrowserContext>(() => ({userId, scope: workspace.scope}), [userId, workspace.scope]);
  const conversations = useConversationDirectory(workspace.scope, context);
  const selectedId = conversations.selectedId ?? '';
  const [formDraft, setFormDraft] = useState<{ id: string; values: ConfigurationDraft } | null>(null);
  const [configState, setConfigState] = useState<{ id: string; config: ChatTestConfig } | null>(null);
  const config = configState?.id === selectedId ? configState.config : null;
  const [optionsState, setOptionsState] = useState<{
    id: string;
    options: ChatModelOption[];
    error?: unknown;
    loading?: boolean
  } | null>(null);
  const options = optionsState?.id === selectedId ? optionsState.options : [];
  const [dirtyState, setDirtyState] = useState<{ id: string; dirty: boolean }>({id: '', dirty: false});
  const dirty = dirtyState.id === selectedId && dirtyState.dirty;
  const [warning, setWarning] = useState<string | null>(null);
  const [aiSettingsUnsafe, setAiSettingsUnsafe] = useState(false);
  useEffect(() => {
    const changed = (event: StorageEvent) => {
      if ([storageKey(context, 'configuration', selectedId), storageKey(context, 'recentConfiguration')].includes(event.key ?? '')) setWarning('另一标签页更新了配置偏好，请刷新目录并重新校验；当前选择和原操作保持不变。');
    };
    window.addEventListener('storage', changed);
    return () => window.removeEventListener('storage', changed);
  }, [context, selectedId]);
  const [invalidation, setInvalidation] = useState(0);
  const [externalModel, setExternalModel] = useState<{ key: string; revision: number }>();
  const [editingId, setEditingId] = useState(selectedId);
  const [applyRevision, setApplyRevision] = useState(0);
  const [cancelRevision, setCancelRevision] = useState(0);
  useEffect(() => {
    setEditingId(selectedId);
    setApplyRevision(0);
    setCancelRevision(0);
    setInvalidation(0);
    setExternalModel(undefined);
  }, [selectedId]);
  const layout = useChatLayout();
  const [tab, setTab] = useState('models');
  useEffect(() => {
    if (workspaceRevision > 0) {
      setTab('workspace');
      layout.open('settings');
    }
  }, [workspaceRevision, layout.open]);
  const [accessHelpOpen, setAccessHelpOpen] = useState(false);
  const [discoveryRefresh, setDiscoveryRefresh] = useState({id: '', revision: 0});
  const [interaction, setInteraction] = useState<ChatInteractionState>({pending: false, active: false});
  const [switchAction, setSwitchAction] = useState<(() => void) | null>(null);
  const afterApply = useRef<(() => void) | null>(null);
  const cache = useMemo(() => {
    const saved = readValue<ChatTestConfig>(storageKey(context, 'configuration', selectedId), true);
    const recent = saved.value ? saved : readValue<ChatTestConfig>(storageKey(context, 'recentConfiguration'), true);
    const parsed = chatConfigSchema.safeParse(recent.value);
    return {
      value: parsed.success && sameScope(parsed.data, workspace.scope) ? parsed.data : null,
      warning: recent.warning ?? (recent.value && !parsed.success ? '已保存配置失效，请重新选择。' : null)
    };
  }, [selectedId, context]);
  const discovery = useConfigurationDiscovery();

  function activate(value: ChatTestConfig, option: ChatModelOption) {
    if (interaction.unsafeToLeave) {
      setWarning('未确认操作未能保存，请先确认原操作后再应用其他配置。');
      return;
    }
    setFormDraft(null);
    setConfigState({id: selectedId, config: value});
    setOptionsState(previous => ({
      id: selectedId,
      options: previous?.id === selectedId && previous.options.length ? previous.options : [option]
    }));
    setDirtyState({id: selectedId, dirty: false});
    if (!writeValue(storageKey(context, 'configuration', selectedId), value, true)
      || !writeValue(storageKey(context, 'recentConfiguration'), value, true)) setWarning('已应用，本地保存失败，刷新后可能需要重设。');
    const next = afterApply.current;
    afterApply.current = null;
    next?.();
  }

  function guard(proceed: () => void) {
    void conversations.finishEdit().then(ready => {
      if (!ready) return;
      if (aiSettingsUnsafe || interaction.unsafeToLeave || conversations.unsafeToLeave) {
        setWarning('未确认操作未能保存，请保留当前会话并确认原操作后再切换。');
        return;
      }
      if (dirty) {
        setSwitchAction(() => proceed);
        return;
      }
      proceed();
    });
  }

  useEffect(() => {
    protectRef.current = guard;
    return () => {
      if (protectRef.current === guard) protectRef.current = null;
    };
  }, [guard]);
  const rejectConfiguration: ConfigurationRejected = (request, error) => {
    if (!sameScope(request.scope, workspace.scope)) return;
    if (config && (error.httpStatus === 401 || error.httpStatus === 403 || (request.binding.id === config.bindingId && request.binding.version === config.bindingVersion && request.budgetRef === config.budgetRef))) {
      setConfigState(null);
      setDirtyState({id: selectedId, dirty: true});
      setInvalidation(value => value + 1);
      removeValue(storageKey(context, 'configuration', selectedId), true);
      removeValue(storageKey(context, 'recentConfiguration'), true);
      setWarning('原配置已失效，请刷新目录并明确应用配置。');
    }
  };

  function chooseModel(key: string) {
    const option = options.find(item => modelOptionKey(item) === key);
    if (!option) return;
    // A choice requiring a budget stays a draft; all entry points use this same application flow.
    setExternalModel({key, revision: (externalModel?.revision ?? 0) + 1});
    setTab('models');
    layout.open('settings');
  }

  function cancel() {
    setFormDraft(null);
    setDirtyState({id: selectedId, dirty: false});
    setCancelRevision(value => value + 1);
    afterApply.current = null;
  }

  const listAccessIssue = aiReadAccessIssue(conversations.listError);
  const modelsAccessIssue = aiReadAccessIssue(optionsState?.id === selectedId ? optionsState.error : null);
  const readBlocked = listAccessIssue != null;

  function recheckAccess() {
    void conversations.refreshFirst();
    setDiscoveryRefresh(value => ({id: selectedId, revision: value.id === selectedId ? value.revision + 1 : 1}));
  }

  const accessActions = listAccessIssue === 'login'
    ? <Button type="primary" href="/user/login">重新登录</Button>
    : <Flex justify="center" gap={8} wrap>
      <Button type="primary" onClick={onChooseWorkspace}>切换工作空间</Button>
      <Button onClick={() => setAccessHelpOpen(true)}>查看开通说明</Button>
      <Button loading={conversations.listLoading} onClick={recheckAccess}>重新检查</Button>
    </Flex>;
  const createConversation = () => guard(() => {
    void conversations.create().then(created => {
      if (created) layout.open('chat');
    });
  });
  const list = <ConversationDirectorySidebar directory={conversations} readBlocked={readBlocked}
                                          writable={workspace.state === 0 || workspace.state === null}
                                          onCreate={createConversation}
                                          header={<ConversationDirectoryHeader userId={userId} scope={workspace.scope}
                                                                               name={workspace.name}
                                                                               writable={!readBlocked && (workspace.state === 0 || workspace.state === null)}
                                                                               creating={conversations.creating}
                                                                               pendingCreate={conversations.hasPendingCreate}
                                                                               onCreate={createConversation}
                                                                               refreshing={conversations.listLoading}
                                                                               onRefresh={() => void conversations.refreshFirst()}
                                                                               onConnect={onConnect}
                                                                               onPlatformConfig={onPlatformConfig}
                                                                               onCollapse={() => layout.close('list')}
                                                                               onManage={() => {
                                                                                 setTab('workspace');
                                                                                 layout.open('settings');
                                                                               }}/>}
                                          onOpen={id => guard(() => {
                                            void conversations.select(id).then(opened => {
                                              if (opened) layout.open('chat');
                                            });
                                          })}/>;

  const editor = <Flex component="aside" vertical className="ainew-settings" aria-label="辅助设置">
    <Flex component="header" align="center" justify="space-between" gap={4}
          className="ainew-settings-header"><Typography.Text strong>设置与管理</Typography.Text>
      <Button type="text" aria-label="收起设置侧栏" icon={<DynamicIcon iconName="MenuFoldOutlined"/>}
              onClick={() => layout.close('settings')}/></Flex>
    <Tabs activeKey={tab} onChange={setTab} destroyOnHidden={false} items={[
      {key: 'workspace', label: '工作空间', forceRender: true, children: workspaceManager},
      {
        key: 'settings',
        label: '会话设置',
        children: <>
          <Descriptions column={1} size="small" className="ainew-info" items={[
            {
              key: 'model',
              label: '当前',
              children: options.find(item => config && item.binding.id === config.bindingId && item.binding.version === config.bindingVersion)?.displayName ?? '尚未应用模型'
            },
            {key: 'budget', label: '预算', children: config?.budgetRef ?? '尚未选择'},
          ]}/><Button
          onClick={() => setTab('models')}>选择模型与生成参数</Button>
          <Typography.Paragraph>应用后用于此会话之后的新消息；保存在当前浏览器。已发送消息保留原配置。</Typography.Paragraph>
          {conversations.selected && <Collapse ghost size="small" className="ainew-info-collapse"
                                               styles={{header: {padding: '8px 0'}, body: {padding: '0 0 8px'}}}
                                               items={[{
                                                 key: 'conversation', label: '会话信息', children:
                                                   <Descriptions column={1} size="small" className="ainew-info" items={[
                                                     {
                                                       key: 'id',
                                                       label: '会话',
                                                       children: conversations.selected.conversationId
                                                     },
                                                     {
                                                       key: 'version',
                                                       label: '版本',
                                                       children: conversations.selected.version
                                                     },
                                                     {
                                                       key: 'created',
                                                       label: '创建',
                                                       children: conversations.selected.createdAt
                                                     },
                                                     {
                                                       key: 'updated',
                                                       label: '更新',
                                                       children: conversations.selected.updatedAt
                                                     },
                                                   ]}/>
                                               }]}/>}</>
      },
      {
        key: 'models',
        label: '模型管理',
        children: <>
          <Typography.Paragraph>当前可用目录；连接和发布配置由服务器维护。</Typography.Paragraph>{!options.length &&
          (optionsState?.loading ? <Flex align="center" gap={8} role="status" className="ainew-loading">
            <Spin size="small"/><Typography.Text>正在加载可用模型…</Typography.Text>
          </Flex> : <Typography.Paragraph>{modelsAccessIssue === 'forbidden' ? '请联系管理员确认此空间的 AI 使用权限。'
            : modelsAccessIssue === 'login' ? '请重新登录后查看可用模型。'
              : optionsState?.error ? '可用模型暂时无法读取，请重新加载。'
                : '此空间尚无可用 AI 配置，请联系管理员确认模型和预算配置。'}</Typography.Paragraph>)}{options.map(item =>
          <Card
            size="small" className="ainew-model-card" key={modelOptionKey(item)} styles={{body: {padding: 12}}}>
            <Flex vertical gap={8} align="flex-start">
              <Typography.Text strong>{item.displayName}</Typography.Text><Typography.Paragraph type="secondary"
                                                                                                className="ainew-muted">文本／流式
              · {item.binding.id}@{item.binding.version}</Typography.Paragraph>
              <Typography.Paragraph type="secondary" className="ainew-muted">窗口 {item.contextWindowTokens} Token ·
                输出上限 {item.limits.maxOutputTokens} ·
                兼容预算 {item.budgetRefs.length} 个</Typography.Paragraph>
              <Button size="small"
                      onClick={() => chooseModel(modelOptionKey(item))}>{config && item.binding.id === config.bindingId && item.binding.version === config.bindingVersion ? '已应用 · 调整参数' : '用于此会话'}</Button>
            </Flex>
          </Card>)}</>
      },
      {
        key: 'workspace-ai',
        label: '空间 AI 配置',
        children: <WorkspaceAiSettings scope={workspace.scope} context={context} onUnsafeChange={setAiSettingsUnsafe}
                                       onUpdated={() => {
                                         setDiscoveryRefresh(value => ({
                                           id: selectedId,
                                           revision: value.id === selectedId ? value.revision + 1 : 1
                                         }));
                                       }}/>
      },
      {
        key: 'assistants',
        label: '助手管理',
        children: <Typography.Paragraph>当前：通用文本聊天。助手模板尚未接入新 AI 链路。</Typography.Paragraph>
      },
      {
        key: 'knowledge',
        label: '知识库引用',
        children: <Typography.Paragraph>知识库引用将在后续版本开放，当前回答不会从文章知识库检索。</Typography.Paragraph>
      },
    ]}/>
    <Flex vertical className="ainew-configuration" hidden={tab !== 'models'}>
      {dirty && <Tag color="orange">有未应用更改</Tag>}
      {cache.warning && <Alert type="warning" title={cache.warning}/>}
      <ChatConfigurationForm sharedDiscovery={discovery} key={selectedId}
                             draftValues={formDraft?.id === selectedId ? formDraft.values : undefined}
                             initialDirty={dirty} onDraftChange={values => setFormDraft({id: selectedId, values})}
                             fixedScope={workspace.scope} restored={cache.value} applied={config}
                             invalidationRevision={editingId === selectedId ? invalidation : 0}
                             externalModel={editingId === selectedId ? externalModel : undefined}
                             applyRevision={editingId === selectedId ? applyRevision : 0}
                             cancelRevision={editingId === selectedId ? cancelRevision : 0}
                             refreshRevision={discoveryRefresh.id === selectedId ? discoveryRefresh.revision : 0}
                             onOptions={(items, error, loading) => setOptionsState({
                               id: selectedId,
                               options: items,
                               error,
                               loading
                             })}
                             onDirty={() => setDirtyState({id: selectedId, dirty: true})} onApply={activate}
                             onRestore={activate}
                             onCancel={cancel} onReset={() => {
        setFormDraft(null);
        setConfigState(null);
        setDirtyState({id: selectedId, dirty: false});
        removeValue(storageKey(context, 'configuration', selectedId), true);
      }}
                             t={t} translate={translate}/>
    </Flex>
  </Flex>;
  const content = <Flex component="section" vertical className="ainew-main">
    <Flex component="header" align="center" justify="space-between" gap={8} wrap className="ainew-conversation-header">
      <Flex vertical className="ainew-conversation-heading"><Typography.Title
        level={2}>{conversations.selected?.title ?? 'AI 聊天（新版）'}</Typography.Title>
        <Typography.Text type="secondary" className="ainew-muted">通用文本聊天</Typography.Text></Flex>
    </Flex>
    {(warning || conversations.storageWarning || conversations.selectionNotice) &&
      <Alert type="warning" title={warning ?? conversations.storageWarning ?? conversations.selectionNotice}/>}
    {!readBlocked && optionsState?.id === selectedId && !optionsState.loading && !options.length &&
      <Alert type="info" showIcon
             title={modelsAccessIssue === 'login' ? '登录已失效，请重新登录。'
               : modelsAccessIssue === 'forbidden' ? '当前账号暂无此空间的 AI 使用权限'
                 : optionsState.error && !(optionsState.error instanceof AiApiError && optionsState.error.code === '205001')
                   ? '可用模型暂时加载失败，请重试。'
                   : '此空间尚无可用 AI 模型或兼容预算。'}
             description={modelsAccessIssue === 'login' ? '重新登录后再检查可用模型和会话。'
               : modelsAccessIssue === 'forbidden' ? '你仍可阅读已获授权的会话。请联系管理员确认 AI 使用权限，或切换到可用的工作空间。'
                 : optionsState.error && !(optionsState.error instanceof AiApiError && optionsState.error.code === '205001')
                   ? '请检查网络连接后重新加载；已获授权的会话仍可阅读。'
                   : '请联系管理员确认当前空间的模型和预算配置。已获授权的会话仍可阅读。'}
             action={modelsAccessIssue === 'login' ? <Button href="/user/login">重新登录</Button>
               : modelsAccessIssue === 'forbidden' ?
                 <Button onClick={() => setAccessHelpOpen(true)}>查看开通说明</Button>
                 : <Button onClick={() => {
                   layout.open('settings');
                   setTab('models');
                 }}>查看模型设置</Button>}/>}
    {(workspace.state !== 0 && workspace.state !== null) && <Alert type="info" title="当前空间只读，历史仍按授权读取。"/>}
    {readBlocked && !conversations.selected ?
      <Flex vertical align="center" justify="center" gap={16} className="ainew-empty" aria-label="AI 访问提示">
        <Result className="ainew-access-state" icon={<LockOutlined/>}
                title={listAccessIssue === 'login' ? '登录已失效，请重新登录' : '此空间的 AI 功能暂不可用'}
                subTitle={listAccessIssue === 'login' ? '重新登录后，可以继续检查当前空间的会话和 AI 使用权限。'
                  : `当前账号暂时无法访问「${workspace.name}」的 AI 功能。创建工作空间不会自动开通 AI 权限，请联系管理员确认。`}
                extra={accessActions}/>
      </Flex> : conversations.detailLoading ?
        <Flex vertical align="center" justify="center" gap={16} className="ainew-empty" role="status">
          <Spin size="small"/><Typography.Text>正在读取会话…</Typography.Text>
        </Flex> : conversations.detailError ?
          <Alert type="error" title="会话详情读取失败" action={<Button
            onClick={() => conversations.selectedId && void conversations.select(conversations.selectedId)}>重试</Button>}/> : conversations.selected ?
            <BusinessChatPanel key={conversations.selected.conversationId} context={context} config={config}
                               options={options} dirty={dirty} conversation={conversations.selected}
                               onUpdated={conversations.updateConversation}
                               onTitleUpdated={conversations.onTitleUpdated}
                               t={t}
                               onConfigurationRejected={rejectConfiguration} onChooseModel={chooseModel}
                               onInteraction={setInteraction}
                               writable={workspace.state === 0 || workspace.state === null}/>
            : conversations.listLoading ?
              <Flex vertical align="center" justify="center" gap={16} className="ainew-empty" role="status">
                <Spin size="small"/><Typography.Text>正在读取会话列表…</Typography.Text></Flex>
              : conversations.listError ?
                <Flex vertical align="center" justify="center" gap={16} className="ainew-empty"><Empty
                  description="暂时无法加载会话，请稍后重试。"/></Flex>
                : <Flex vertical align="center" justify="center" gap={16} className="ainew-empty"><Empty
                  description={conversations.total === 0
                    ? '此空间还没有会话，可以新建一个会话开始。' : '选择一个会话继续聊天。'}/><Button
                  onClick={() => {
                    layout.open('settings');
                    setTab('models');
                  }}>查看可用模型</Button></Flex>}
  </Flex>;
  return <Flex vertical className="ainew-workspace">
    <ChatWorkspaceLayout layout={layout} list={list} settings={editor}>
      {content}
    </ChatWorkspaceLayout>
    <Modal title="如何开通此空间的 AI 功能" open={accessHelpOpen}
           onCancel={() => setAccessHelpOpen(false)}
           footer={<Button onClick={() => setAccessHelpOpen(false)}>知道了</Button>}>
      <Typography.Paragraph>创建工作空间后，AI 使用权限需要单独开通。空间负责人身份也不代表已获得 AI
        使用权限。</Typography.Paragraph>
      <Typography className="ainew-access-instructions">
        <ol>
          <li>联系管理员，确认当前账号在此空间的 AI 使用权限与会话访问权限。</li>
          <li>由管理员检查此空间是否已配置可用模型和预算。</li>
          <li>开通后点击“重新检查”；也可从顶部选择已有 AI 权限的工作空间。</li>
        </ol>
      </Typography>
      <Collapse ghost size="small" className="ainew-info-collapse"
                styles={{header: {padding: '8px 0'}, body: {padding: '0 0 8px'}}}
                items={[{
                  key: 'support', label: '提供给管理员的空间信息', children:
                    <Descriptions column={1} size="small" className="ainew-info" items={[
                      {key: 'name', label: '空间', children: workspace.name},
                      {key: 'tenant', label: '租户标识', children: workspace.scope.tenantId},
                      {key: 'workspace', label: '空间标识', children: workspace.scope.workspaceId},
                    ]}/>
                }]}/>
    </Modal>
    <Modal title="有未应用配置" open={!!switchAction} footer={<Flex justify="flex-end" gap={8} wrap>
      <Button onClick={() => {
        setSwitchAction(null);
        afterApply.current = null;
      }}>留在当前会话</Button>
      <Button onClick={() => {
        const next = switchAction;
        cancel();
        setSwitchAction(null);
        next?.();
      }}>放弃修改并切换</Button>
      <Button type="primary" onClick={() => {
        afterApply.current = switchAction;
        setSwitchAction(null);
        layout.open('settings');
        setTab('models');
        setApplyRevision(value => value + 1);
      }}>应用并切换</Button>
    </Flex>} onCancel={() => {
      setSwitchAction(null);
      afterApply.current = null;
    }}>
      <Typography.Paragraph>配置草稿需要先应用或放弃。{interaction.pending ? '未确认写操作已按原会话保存，返回后确认原操作。' : ''}切换不会停止后端执行。</Typography.Paragraph>
    </Modal>
  </Flex>;
}

export default function ChatNewPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const pageRef = useRef<HTMLElement>(null);
  const [pageTop, setPageTop] = useState(0);
  const pageHeight = useComponentHeight(pageTop, 0);
  useLayoutEffect(() => {
    const page = pageRef.current;
    if (!page) return;
    const measureTop = () => {
      // 缓存页隐藏时不采用零坐标；全局标签栏变化后重新测量实际占用。
      if (page.getClientRects().length) setPageTop(Math.max(0, page.getBoundingClientRect().top));
    };
    measureTop();
    const observer = new ResizeObserver(measureTop);
    observer.observe(page);
    if (page.parentElement) observer.observe(page.parentElement);
    window.addEventListener('resize', measureTop);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', measureTop);
    };
  }, [userId]);
  const intl = useIntl();
  const translate = (id: string) => intl.formatMessage({
    id,
    defaultMessage: messages[id as keyof typeof messages] ?? id
  });
  const t = (key: string) => translate(`app.aiChat.${key}`);
  const current = useCurrentWorkspace(userId);
  const protect = useRef<((proceed: () => void) => void) | null>(null);
  const [connectionOpen, setConnectionOpen] = useState(false);
  const [workspaceRevision, setWorkspaceRevision] = useState(0);
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [catalogUnsafe, setCatalogUnsafe] = useState(false);
  const [preparationUnsafe, setPreparationUnsafe] = useState(false);
  const [aiPreparationRevision, setAiPreparationRevision] = useState(0);
  const catalogContext = useMemo<BrowserContext>(() => ({
    userId,
    scope: {tenantId: 'control-admin', workspaceId: 'catalog'}
  }), [userId]);
  const [tenantId, setTenantId] = useState('');
  const [workspaceId, setWorkspaceId] = useState('');
  const fixedScopeKey = `arte.ainew.fixedScope.v1.${encodeURIComponent(JSON.stringify([userId]))}`;
  const [legacy, setLegacy] = useState<{ userId: string; scope: AiScope } | null>(() => {
    const candidate = readValue<AiScope>(fixedScopeKey).value;
    const parsed = chatScopeSchema.safeParse(candidate);
    return parsed.success ? {userId, scope: parsed.data} : null;
  });
  const workspace = legacy?.userId === userId ? {
    scope: legacy.scope,
    name: '手动连接的 AI 工作空间',
    state: null
  } : current;
  const previousAccount = useRef(userId);
  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (catalogUnsafe || preparationUnsafe) return;
    if (protect.current) protect.current(proceed); else proceed();
  }), [userId, catalogUnsafe, preparationUnsafe]);
  useEffect(() => {
    const selected = (event: Event) => {
      if ((event as CustomEvent).detail.userId !== userId) return;
      removeValue(fixedScopeKey);
      setLegacy(null);
    };
    window.addEventListener('arte-workspace-selected', selected);
    return () => window.removeEventListener('arte-workspace-selected', selected);
  }, [userId, fixedScopeKey]);
  useEffect(() => {
    if (previousAccount.current && previousAccount.current !== userId) {
      clearAccountSession(previousAccount.current);
      setCatalogOpen(false);
      setCatalogUnsafe(false);
      setPreparationUnsafe(false);
    }
    previousAccount.current = userId;
  }, [userId]);
  if (!userId) return <Alert type="info" title="请登录后使用 AI 聊天。"/>;
  // 空间尚未就绪时列表未挂载，保留同一菜单作为连接固定授权空间的入口。
  const workspaceFallback = <>
    <Flex className="ainew-bootstrap-workspace"><WorkspaceQuickSelect userId={userId} scope={workspace?.scope}
                                                                      name={workspace?.name}
                                                                      onManage={() => setWorkspaceRevision(value => value + 1)}
                                                                      onConnect={() => setConnectionOpen(true)}
                                                                      onPlatformConfig={() => setCatalogOpen(true)}/>
    </Flex>
    <WorkspacePicker userId={userId} presentation="inline"/>
  </>;
  return <Flex component="main" vertical gap={12} ref={pageRef} className="ainew-page" style={{height: pageHeight}}
               aria-label="AI 聊天（新版）">
    {catalogUnsafe && <Alert type="warning" title="平台配置操作尚未可靠保存，请确认原操作后再切换空间。"/>}
    <Drawer title="平台 AI 配置" open={catalogOpen} onClose={() => !catalogUnsafe && setCatalogOpen(false)} size={640}>
      <WorkspaceAiSettings key={userId} scope={catalogContext.scope} context={catalogContext} adminOnly
                           onUnsafeChange={setCatalogUnsafe}
                           onUpdated={() => setAiPreparationRevision(value => value + 1)}/>
    </Drawer>
    {legacy?.userId === userId && <Alert type="info" title="当前使用手动指定的已有 AI scope" description={<>
      <Descriptions column={1} size="small" className="ainew-info" items={[
        {key: 'tenant', label: '租户', children: legacy.scope.tenantId},
        {key: 'workspace', label: '空间', children: legacy.scope.workspaceId},
      ]}/>
      用于固定授权过渡。公共默认空间不会自动继承此处模型、预算和历史；后端仍检查当前账号授权。</>}/>}
    {workspace ?
      <WorkspaceAiPreparation key={JSON.stringify([userId, workspace.scope])} scope={workspace.scope}
                              context={{userId, scope: workspace.scope}} legacy={workspace.state === null}
                              refreshRevision={aiPreparationRevision} onUnsafeChange={setPreparationUnsafe}
                              fallback={workspaceFallback}>
        <WorkspaceChat userId={userId} workspace={workspace} t={t} translate={translate} protectRef={protect}
                       onChooseWorkspace={() => setWorkspaceRevision(value => value + 1)}
                       workspaceRevision={workspaceRevision}
                       onConnect={() => setConnectionOpen(true)} onPlatformConfig={() => setCatalogOpen(true)}
                       workspaceManager={<WorkspacePicker userId={userId} presentation="inline"/>}/>
      </WorkspaceAiPreparation>
      : <Flex vertical className="ainew-bootstrap"><Typography.Paragraph>请选择或初始化个人默认空间；也可连接当前账号已有的合法
        AI 工作空间。</Typography.Paragraph>
        {workspaceFallback}</Flex>}
    <Modal title="连接已有 AI 工作空间" open={connectionOpen} onCancel={() => setConnectionOpen(false)}
           okButtonProps={{disabled: !chatScopeSchema.safeParse({tenantId, workspaceId}).success}} onOk={() => {
      const scope = chatScopeSchema.parse({tenantId, workspaceId});
      const proceed = () => {
        writeValue(fixedScopeKey, scope);
        setLegacy({userId, scope});
        setConnectionOpen(false);
      };
      if (catalogUnsafe || preparationUnsafe) return;
      if (protect.current) protect.current(proceed); else proceed();
    }}>
      <Typography.Paragraph>填写当前账号已有授权的真实范围。发现、会话及发送仍由后端授权检查。</Typography.Paragraph>
      <Form layout="vertical" name="ai-new-workspace-connection" component="div" className="ainew-connection-form">
        <Form.Item label="租户 ID" htmlFor="ai-new-tenant-id" required>
          <Input id="ai-new-tenant-id" aria-label="已有 AI 租户 ID" placeholder="租户 ID" maxLength={256}
                 value={tenantId}
                 onChange={event => setTenantId(event.target.value)}/>
        </Form.Item>
        <Form.Item label="工作空间 ID" htmlFor="ai-new-workspace-id" required>
          <Input id="ai-new-workspace-id" aria-label="已有 AI 空间 ID" placeholder="工作空间 ID" maxLength={256}
                 value={workspaceId}
                 onChange={event => setWorkspaceId(event.target.value)}/>
        </Form.Item>
      </Form>
    </Modal>
  </Flex>;
}
