import React, {useEffect, useMemo, useRef, useState} from 'react';
import {useIntl, useModel} from '@umijs/max';
import {Alert, Button, Drawer, Empty, Input, Modal, Pagination, Splitter, Tabs, Tag} from 'antd';
import type {AiScope, ChatModelOption, ConversationResponse} from '@/services/arte-ai';
import {getConversation} from '@/services/arte-ai';
import messages from '@/locales/zh-CN/aiChat';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import {useCurrentWorkspace} from '@/features/workspace/useWorkspace';
import type {WorkspaceView} from '@/services/platform/workspace';
import ChatConfigurationForm, {type ConfigurationDraft} from '@/features/ainew/ChatConfigurationForm';
import BusinessChatPanel, {type ChatInteractionState} from '@/features/ainew/BusinessChatPanel';
import {chatConfigSchema, chatScopeSchema, type ChatTestConfig, modelOptionKey} from '@/features/ainew/config';
import {useConversations} from '@/features/ainew/useConversations';
import {type ConfigurationRejected} from '@/features/ainew/configurationInvalidation';
import {
  type BrowserContext,
  clearAccountSession,
  readValue,
  removeValue,
  sameScope,
  STORAGE_WARNING,
  storageKey,
  writeValue
} from '@/features/ainew/storage';
import './style.css';

function WorkspaceChat({userId, workspace, t, translate, protectRef}: {
  userId: string;
  workspace: Pick<WorkspaceView, 'scope' | 'name'> & { state: WorkspaceView['state'] | null };
  t: (key: string) => string;
  translate: (id: string) => string;
  protectRef: React.RefObject<((proceed: () => void) => void) | null>;
}) {
  const context = useMemo<BrowserContext>(() => ({userId, scope: workspace.scope}), [userId, workspace.scope]);
  const conversations = useConversations(workspace.scope, context);
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
  const [panelOpen, setPanelOpen] = useState(false);
  const [tab, setTab] = useState('settings');
  const [listOpen, setListOpen] = useState(false);
  const [filter, setFilter] = useState('');
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState('新会话');
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
  const [pins, setPins] = useState<string[]>(() => {
    const stored = readValue<unknown>(storageKey(context, 'pins'), true).value;
    return Array.isArray(stored) ? stored.filter((id): id is string => typeof id === 'string').slice(0, 100) : [];
  });
  const [pinnedRecords, setPinnedRecords] = useState<Record<string, ConversationResponse | null>>({});
  useEffect(() => {
    const controller = new AbortController();
    for (const id of pins) void getConversation({
      scope: workspace.scope,
      conversationId: id
    }, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted) setPinnedRecords(previous => ({...previous, [id]: body.data}));
      })
      .catch(() => {
        if (!controller.signal.aborted) setPinnedRecords(previous => ({...previous, [id]: null}));
      });
    return () => controller.abort();
  }, [pins, context]);

  function togglePin(id: string) {
    const next = pins.includes(id) ? pins.filter(pin => pin !== id) : [...pins, id].slice(0, 100);
    setPins(next);
    if (!writeValue(storageKey(context, 'pins'), next, true)) setWarning(STORAGE_WARNING);
  }

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
    if (interaction.unsafeToLeave || conversations.unsafeToLeave) {
      setWarning('未确认操作未能保存，请保留当前会话并确认原操作后再切换。');
      return;
    }
    if (dirty) {
      setSwitchAction(() => proceed);
      return;
    }
    proceed();
  }

  protectRef.current = guard;
  useEffect(() => () => {
    protectRef.current = null;
  }, []);
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
    setPanelOpen(true);
  }

  function cancel() {
    setFormDraft(null);
    setDirtyState({id: selectedId, dirty: false});
    setCancelRevision(value => value + 1);
    afterApply.current = null;
  }

  const [layout] = useState(() => readValue<{
    left: number;
    right: number
  }>(storageKey(context, 'layout'), true).value);
  const [widths, setWidths] = useState({
    left: Number.isFinite(layout?.left) ? layout!.left : 260,
    right: Number.isFinite(layout?.right) ? layout!.right : 320
  });
  const [desktop, setDesktop] = useState(() => window.matchMedia('(min-width: 1280px)').matches);
  const [wideList, setWideList] = useState(() => window.matchMedia('(min-width: 768px)').matches);
  useEffect(() => {
    const wide = window.matchMedia('(min-width: 1280px)');
    const medium = window.matchMedia('(min-width: 768px)');
    const update = () => {
      setDesktop(wide.matches);
      setWideList(medium.matches);
    };
    wide.addEventListener('change', update);
    medium.addEventListener('change', update);
    return () => {
      wide.removeEventListener('change', update);
      medium.removeEventListener('change', update);
    };
  }, []);
  const list = <aside className="ainew-sidebar" aria-label="会话列表">
    <Button block type="primary"
            disabled={(workspace.state !== 0 && workspace.state !== null) || conversations.creating}
            onClick={() => guard(() => {
              setTitle(conversations.pendingTitle ?? '新会话');
              setCreating(true);
            })}>新建会话</Button>
    <Input aria-label="筛选本页会话" placeholder="筛选本页会话" value={filter}
           onChange={event => setFilter(event.target.value)}/>
    <Button size="small" loading={conversations.listLoading}
            onClick={() => void conversations.loadPage(conversations.page)}>刷新列表</Button>
    {pins.length > 0 &&
      <section><h3>在此浏览器置顶</h3>{pins.map(id => <div className="ainew-conversation-row" key={id}>
        <Button type="text" onClick={() => guard(() => {
          void conversations.select(id);
          setListOpen(false);
        })}>{pinnedRecords[id]?.title ?? (pinnedRecords[id] === null ? '记录无法读取，可重试或移除' : '读取中…')}</Button>
        <Button size="small" onClick={() => togglePin(id)}>取消置顶</Button>
      </div>)}</section>}
    {conversations.selected && !conversations.records.some(record => record.conversationId === conversations.selectedId) &&
      <section><h3>当前会话</h3><p>{conversations.selected.title}</p><Button size="small"
                                                                             onClick={() => togglePin(conversations.selectedId!)}>{pins.includes(conversations.selectedId!) ? '取消置顶' : '在此浏览器置顶'}</Button>
      </section>}
    {conversations.listError != null && <Alert type="error" title="会话列表读取失败" action={<Button
      onClick={() => void conversations.loadPage(conversations.page)}>重试</Button>}/>}
    {!conversations.listLoading && !conversations.listError && !conversations.records.filter(record => record.title.includes(filter)).length &&
      <Empty description="当前页没有匹配会话，可切换页面。"/>}
    <ul
      className="ainew-conversations">{conversations.records.filter(record => record.title.includes(filter) && !pins.includes(record.conversationId)).map(record =>
      <li key={record.conversationId}>
        <Button block type={record.conversationId === conversations.selectedId ? 'primary' : 'text'}
                onClick={() => guard(() => {
                  void conversations.select(record.conversationId);
                  setListOpen(false);
                })}>{record.title}</Button>
        <div className="ainew-conversation-row">
          <time>{new Date(record.updatedAt).toLocaleString()}</time>
          <Button size="small" onClick={() => togglePin(record.conversationId)}>置顶</Button></div>
      </li>)}</ul>
    <Pagination simple hideOnSinglePage current={conversations.page} total={conversations.total} pageSize={20}
                showSizeChanger={false} onChange={page => void conversations.loadPage(page)}/>
  </aside>;
  const editor = <div className="ainew-settings" aria-label="辅助设置">
    <Tabs activeKey={tab} onChange={setTab} items={[
      {
        key: 'settings',
        label: '会话设置',
        children: <>
          <p>当前：{options.find(item => config && item.binding.id === config.bindingId && item.binding.version === config.bindingVersion)?.displayName ?? '尚未应用模型'}</p>
          <p>预算：{config?.budgetRef ?? '尚未选择'}</p><Button
          onClick={() => setTab('models')}>选择模型与生成参数</Button>
          <p>应用后用于此会话之后的新消息；保存在当前浏览器。已发送消息保留原配置。</p>{conversations.selected && <details>
          <summary>会话信息</summary>
          <p>{conversations.selected.conversationId} · 版本 {conversations.selected.version}</p>
          <p>创建：{conversations.selected.createdAt}</p><p>更新：{conversations.selected.updatedAt}</p></details>}</>
      },
      {
        key: 'models',
        label: '模型管理',
        children: <><p>当前可用目录；连接和发布配置由服务器维护。</p>{!options.length &&
          <p>此空间尚无可用 AI 配置，请刷新目录或联系配置人员。</p>}{options.map(item => <article
          className="ainew-model-card" key={modelOptionKey(item)}>
          <strong>{item.displayName}</strong><p className="ainew-muted">文本／流式
          · {item.binding.id}@{item.binding.version}</p>
          <p className="ainew-muted">窗口 {item.contextWindowTokens} Token · 输出上限 {item.limits.maxOutputTokens} ·
            兼容预算 {item.budgetRefs.length} 个</p>
          <Button size="small"
                  onClick={() => chooseModel(modelOptionKey(item))}>{config && item.binding.id === config.bindingId && item.binding.version === config.bindingVersion ? '已应用 · 调整参数' : '用于此会话'}</Button>
        </article>)}</>
      },
      {key: 'assistants', label: '助手管理', children: <p>当前：通用文本聊天。助手模板尚未接入新 AI 链路。</p>},
      {
        key: 'knowledge',
        label: '知识库引用',
        children: <p>知识库引用将在后续版本开放，当前回答不会从文章知识库检索。</p>
      },
    ]}/>
    <div hidden={tab !== 'models'}>
      {dirty && <Tag color="orange">有未应用更改</Tag>}
      {cache.warning && <Alert type="warning" title={cache.warning}/>}
      <ChatConfigurationForm key={selectedId} draftValues={formDraft?.id === selectedId ? formDraft.values : undefined}
                             initialDirty={dirty} onDraftChange={values => setFormDraft({id: selectedId, values})}
                             fixedScope={workspace.scope} restored={cache.value} applied={config}
                             invalidationRevision={editingId === selectedId ? invalidation : 0}
                             externalModel={editingId === selectedId ? externalModel : undefined}
                             applyRevision={editingId === selectedId ? applyRevision : 0}
                             cancelRevision={editingId === selectedId ? cancelRevision : 0}
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
    </div>
  </div>;
  const content = <section className="ainew-main">
    <header className="ainew-conversation-header"><Button className="ainew-list-toggle"
                                                          onClick={() => wideList ? setWidths(previous => ({
                                                            ...previous,
                                                            left: previous.left ? 0 : layout?.left ?? 260
                                                          })) : setListOpen(true)}>会话列表</Button>
      <div><h2>{conversations.selected?.title ?? 'AI 聊天（新版）'}</h2><span className="ainew-muted">通用文本聊天</span>
      </div>
      <Button onClick={() => {
        setPanelOpen(value => !value);
        if (!config) setTab('models');
      }}>设置</Button></header>
    {(warning || conversations.storageWarning || conversations.selectionNotice) &&
      <Alert type="warning" title={warning ?? conversations.storageWarning ?? conversations.selectionNotice}/>}
    {optionsState?.id === selectedId && !optionsState.loading && !options.length &&
      <Alert type={optionsState.error ? 'warning' : 'info'}
             title={optionsState.error ? '当前空间的 AI 配置读取失败或尚未授权。' : '此空间尚无可用 AI 模型或兼容预算。'}
             action={<Button onClick={() => {
               setPanelOpen(true);
               setTab('models');
             }}>查看配置与重试</Button>}/>}
    {(workspace.state !== 0 && workspace.state !== null) && <Alert type="info" title="当前空间只读，历史仍按授权读取。"/>}
    {conversations.detailLoading ? <p role="status">正在读取会话…</p> : conversations.detailError ?
      <Alert type="error" title="会话详情读取失败" action={<Button
        onClick={() => conversations.selectedId && void conversations.select(conversations.selectedId)}>重试</Button>}/> : conversations.selected ?
        <BusinessChatPanel key={conversations.selected.conversationId} context={context} config={config}
                           options={options} dirty={dirty} conversation={conversations.selected}
                           onUpdated={conversations.updateConversation} t={t}
                           onConfigurationRejected={rejectConfiguration} onChooseModel={chooseModel}
                           onInteraction={setInteraction} writable={workspace.state === 0 || workspace.state === null}/>
        : <div className="ainew-empty"><Empty description="创建或选择会话即可开始；模型配置不影响会话阅读。"/><Button
          onClick={() => {
            setPanelOpen(true);
            setTab('models');
          }}>查看可用模型</Button></div>}
  </section>;
  return <>
    <div className="ainew-layout"><Splitter onResize={sizes => setWidths(previous => ({
      left: wideList ? sizes[0] : previous.left,
      right: desktop && panelOpen ? sizes[2] : previous.right
    }))} onResizeEnd={sizes => {
      if (!writeValue(storageKey(context, 'layout'), {
        left: wideList ? sizes[0] : layout?.left ?? 260,
        right: desktop && panelOpen ? sizes[2] : layout?.right ?? 320
      }, true)) setWarning(STORAGE_WARNING);
    }}>
      <Splitter.Panel defaultSize={layout?.left ?? 260} size={wideList ? widths.left : 0} min={wideList ? 200 : 0}
                      max="35%" resizable={wideList} collapsible={wideList}>
        {wideList ? list :
          <Drawer title="会话列表" open={listOpen} forceRender getContainer={false} onClose={() => setListOpen(false)}
                  rootStyle={{position: 'fixed'}} styles={{body: {padding: 0}}}>{list}</Drawer>}
      </Splitter.Panel>
      <Splitter.Panel min={280}>{content}</Splitter.Panel>
      <Splitter.Panel defaultSize={layout?.right ?? 320} size={desktop && panelOpen ? widths.right : 0}
                      min={desktop && panelOpen ? 280 : 0} max="40%" resizable={desktop && panelOpen}>
        {desktop ? <aside hidden={!panelOpen} style={{height: '100%'}}>{editor}</aside> :
          <Drawer title="聊天设置" open={panelOpen} forceRender getContainer={false} onClose={() => setPanelOpen(false)}
                  rootStyle={{position: 'fixed'}} styles={{body: {padding: 0}}}>{editor}</Drawer>}
      </Splitter.Panel>
    </Splitter></div>
    <Modal title={conversations.pendingTitle ? '确认原创建操作' : '新建会话'} open={creating}
           onCancel={() => setCreating(false)} confirmLoading={conversations.creating}
           okButtonProps={{disabled: !title.trim() || (workspace.state !== 0 && workspace.state !== null)}}
           onOk={async () => {
             if (await conversations.create(title.trim())) setCreating(false);
           }}>
      <Input aria-label="会话标题" maxLength={256} value={conversations.pendingTitle ?? title}
             disabled={!!conversations.pendingTitle} onChange={event => setTitle(event.target.value)}/>
      {conversations.createError != null && <Alert type="error" title="创建结果未确认或被拒绝，请查看错误后处理。"/>}
    </Modal>
    <Modal title="有未应用配置" open={!!switchAction} footer={<>
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
        setPanelOpen(true);
        setTab('models');
        setApplyRevision(value => value + 1);
      }}>应用并切换</Button>
    </>} onCancel={() => {
      setSwitchAction(null);
      afterApply.current = null;
    }}>
      <p>配置草稿需要先应用或放弃。{interaction.pending ? '未确认写操作已按原会话保存，返回后确认原操作。' : ''}切换不会停止后端执行。</p>
    </Modal>
  </>;
}

export default function ChatNewPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const intl = useIntl();
  const translate = (id: string) => intl.formatMessage({
    id,
    defaultMessage: messages[id as keyof typeof messages] ?? id
  });
  const t = (key: string) => translate(`app.aiChat.${key}`);
  const current = useCurrentWorkspace(userId);
  const protect = useRef<((proceed: () => void) => void) | null>(null);
  const [connectionOpen, setConnectionOpen] = useState(false);
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
  useEffect(() => {
    if (previousAccount.current && previousAccount.current !== userId) clearAccountSession(previousAccount.current);
    previousAccount.current = userId;
  }, [userId]);
  if (!userId) return <Alert type="info" title="请登录后使用 AI 聊天。"/>;
  return <main className="ainew-page" aria-label="AI 聊天（新版）">
    <header className="ainew-page-header"><WorkspacePicker key={userId} userId={userId}
                                                           beforeSelect={(_scope, proceed) => {
                                                             const next = () => {
                                                               removeValue(fixedScopeKey);
                                                               setLegacy(null);
                                                               proceed();
                                                             };
                                                             if (protect.current) protect.current(next); else next();
                                                           }}/><Tag>AI 聊天（新版）</Tag><Button type="text"
                                                                                               onClick={() => setConnectionOpen(true)}>连接已有
      AI 工作空间</Button></header>
    {legacy?.userId === userId && <Alert type="info" title="当前使用手动指定的已有 AI scope" description={<>
      <div style={{overflowWrap: 'anywhere'}}>租户：{legacy.scope.tenantId} · 空间：{legacy.scope.workspaceId}</div>
      用于固定授权过渡。公共默认空间不会自动继承此处模型、预算和历史；后端仍检查当前账号授权。</>}/>}
    {workspace ?
      <WorkspaceChat key={JSON.stringify([userId, workspace.scope])} userId={userId} workspace={workspace} t={t}
                     translate={translate} protectRef={protect}/>
      : <div className="ainew-empty"><Empty
        description="请选择或初始化个人默认空间；也可连接当前账号已有的合法 AI 工作空间。"/></div>}
    <Modal title="连接已有 AI 工作空间" open={connectionOpen} onCancel={() => setConnectionOpen(false)}
           okButtonProps={{disabled: !chatScopeSchema.safeParse({tenantId, workspaceId}).success}} onOk={() => {
      const scope = chatScopeSchema.parse({tenantId, workspaceId});
      const proceed = () => {
        writeValue(fixedScopeKey, scope);
        setLegacy({userId, scope});
        setConnectionOpen(false);
      };
      if (protect.current) protect.current(proceed); else proceed();
    }}><p>填写当前账号已有授权的真实范围。发现、会话及发送仍由后端授权检查。</p><Input aria-label="已有 AI 租户 ID"
                                                                                    placeholder="租户 ID"
                                                                                    maxLength={256} value={tenantId}
                                                                                    onChange={event => setTenantId(event.target.value)}/><Input
      aria-label="已有 AI 空间 ID" placeholder="工作空间 ID" maxLength={256} value={workspaceId}
      onChange={event => setWorkspaceId(event.target.value)}/></Modal>
  </main>;
}
