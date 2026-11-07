import {Alert, Button, Drawer, Empty, Flex, Input, Popconfirm, Spin, Tabs, Tag, Typography} from 'antd';
import {useEffect, useState} from 'react';
import {
  type AssistantContent,
  type AssistantTrial,
  getAssistant,
  getAssistantCapabilities,
  listAssistantVersions,
  listManagedAssistants,
} from '@/services/arte-ai/assistant';
import {
  assistantError,
  assistantStateLabel,
  completeAssistant,
  contentError,
  emptyAssistant,
  useAssistantCommand,
  useAssistantRead
} from './assistantState';
import AssistantTrialPanel from './AssistantTrialPanel';
import type {BrowserContext} from './storage';

const fields = [
  ['displayName', '助手名称', 160], ['description', '用途', 1000],
  ['systemInstruction', '工作指令', 8192], ['expectedOutput', '预期输出', 2000],
] as const;
const templates: AssistantContent[] = [
  {
    displayName: '文章改写助手', description: '改写用户粘贴的文章，保持原意并改善表达。',
    systemInstruction: '根据用户粘贴的原文改写，保留事实、观点与关键限定。缺少原文时先请求补充。超出改写范围的任务请说明边界。不要编造事实或引文。',
    expectedOutput: '先给出改写稿，再列出主要改动。需要补充原文时只提出简短问题。',
    examplePrompts: ['请改写下面的文章，保留原意，并列出主要改动。\n\n'],
  },
  {
    displayName: '研究提纲助手', description: '根据研究主题或材料整理问题、证据和研究提纲。',
    systemInstruction: '只依据用户给出的主题与材料组织提纲。缺少研究对象或关键信息时先追问。把已提供的证据与待核实信息区分开，不编造来源。',
    expectedOutput: '给出研究问题、分层提纲、已有依据和待补充资料。',
    examplePrompts: ['请根据下面的研究主题和材料整理提纲，并标明待核实的问题。\n\n'],
  },
];

function ContentFields({value, editable, onChange}: {
  value: AssistantContent; editable: boolean; onChange: (value: AssistantContent) => void;
}) {
  return <Flex vertical gap={10}>
    {fields.map(([key, label, maxLength]) => <label key={key}>
      <Typography.Text strong>{label}</Typography.Text>
      {key === 'displayName' ?
        <Input aria-label={label} value={value[key]} maxLength={maxLength} readOnly={!editable}
               onChange={event => onChange({...value, [key]: event.target.value})}/> :
        <Input.TextArea aria-label={label} value={value[key]} maxLength={maxLength} readOnly={!editable}
                        autoSize={{minRows: key === 'systemInstruction' ? 3 : 2, maxRows: 10}}
                        onChange={event => onChange({...value, [key]: event.target.value})}/>}
    </label>)}
    <Typography.Text strong>示例问题（最多 5 条）</Typography.Text>
    {value.examplePrompts.map((prompt, index) => <Flex key={index} gap={8}>
      <Input.TextArea aria-label={'示例问题 ' + (index + 1)} value={prompt} maxLength={1000} readOnly={!editable}
                      autoSize={{minRows: 1, maxRows: 4}} onChange={event => onChange({
        ...value, examplePrompts: value.examplePrompts.map((item, i) => i === index ? event.target.value : item),
      })}/>
      {editable && <Button aria-label={'移除示例问题 ' + (index + 1)} onClick={() =>
        onChange({...value, examplePrompts: value.examplePrompts.filter((_, i) => i !== index)})}>移除</Button>}
    </Flex>)}
    {editable && <Button disabled={value.examplePrompts.length >= 5} onClick={() =>
      onChange({...value, examplePrompts: [...value.examplePrompts, '']})}>添加示例问题</Button>}
  </Flex>;
}

export default function AssistantManagement({context, open, writable, onClose, onUnsafeChange, onUpdated}: {
  context: BrowserContext;
  open: boolean;
  writable: boolean;
  onClose: () => void;
  onUnsafeChange: (unsafe: boolean) => void;
  onUpdated: () => void;
}) {
  const {scope} = context;
  const [assistantId, setAssistantId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [edit, setEdit] = useState<AssistantContent | null>(null);
  const [tab, setTab] = useState('draft');
  const [cursor, setCursor] = useState<string | null>(null);
  const [versionCursor, setVersionCursor] = useState<string | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<number>();
  const [accepted, setAccepted] = useState<{ assistantId: string; trial: AssistantTrial } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const list = useAssistantRead(open ? JSON.stringify([cursor]) : null, signal =>
    listManagedAssistants({scope, cursor, limit: 20}, {signal}));
  const caps = useAssistantRead(open ? assistantId ?? '' : null, signal =>
    getAssistantCapabilities({scope, assistantId}, {signal}));
  const detail = useAssistantRead(open && assistantId ? assistantId : null, signal =>
    getAssistant({scope, assistantId: assistantId!}, {signal}));
  const versions = useAssistantRead(open && assistantId && tab === 'versions' ? JSON.stringify([assistantId, versionCursor]) : null, signal =>
    listAssistantVersions({scope, assistantId: assistantId!, cursor: versionCursor, limit: 20}, {signal}));
  const command = useAssistantCommand(context, (result, original) => {
    if (result.kind === 'trial' && original.kind === 'trial') {
      setAssistantId(original.assistantId);
      setCreating(false);
      setTab('trial');
      setAccepted({
        assistantId: original.assistantId, trial: {
          trialId: result.trial.trialId, draftRevision: result.trial.draftRevision,
          invocationId: result.trial.execution.executionId, createdAt: result.trial.execution.acceptedAt,
        }
      });
      setNotice('试用已受理，可查看执行和费用状态。');
      return;
    }
    if (result.kind === 'trial') return;
    setEdit(null);
    setCreating(false);
    setAssistantId(result.receipt.assistantId);
    if (result.kind === 'create' || result.kind === 'copy') {
      setCursor(null);
      setTab('draft');
    }
    list.reload();
    caps.reload();
    detail.reload();
    versions.reload();
    onUpdated();
    setNotice({
      create: '已创建草稿。',
      copy: '已复制为独立草稿。',
      save: '草稿已保存。',
      publish: '新版本已发布。',
      disable: '助手已停用。'
    }[result.kind]);
  });
  const dirty = edit !== null;
  const disabled = !writable || command.busy || !!command.pending || !!command.warning;
  useEffect(() => {
    onUnsafeChange(dirty || command.busy);
    return () => onUnsafeChange(false);
  }, [dirty, command.busy, onUnsafeChange]);

  const saved = detail.value;
  const content = edit ?? saved?.draft.content ?? emptyAssistant;
  const validation = contentError(content);
  const canEdit = creating ? caps.value?.canCreate : caps.value?.canEdit;
  const release = versions.value?.items.find(item => item.version === selectedVersion);

  function select(id?: string, create = false) {
    setAssistantId(id);
    setCreating(create);
    setEdit(null);
    setTab('draft');
    setVersionCursor(null);
    setSelectedVersion(undefined);
    setNotice(null);
  }

  function save() {
    if (!canEdit || validation || disabled) return;
    if (creating) {
      void command.submit({kind: 'create', scope, content});
    } else if (saved) {
      void command.submit({
        kind: 'save',
        scope,
        assistantId: saved.resource.id,
        expectedRevision: saved.draft.revision,
        content
      });
    }
  }

  return <Drawer title="当前空间 · 助手管理" size="min(960px, 100vw)" open={open} onClose={() => {
    if (!dirty && !command.busy) onClose();
    else setNotice('请先保存或放弃草稿修改，并等待正在提交的请求完成。');
  }}>
    <Flex vertical gap={12}>
      {notice && <Alert type="info" title={notice} closable onClose={() => setNotice(null)}/>}
      {command.warning && <Alert type="warning" title={command.warning}/>}
      {command.error && <Alert type="warning" title={command.error}/>}
      {command.pending && <Alert type="warning" title="助手操作结果待确认"
                                 description={'将使用原请求和原幂等键继续确认 · ' + ({
                                   trial: '付费试用',
                                   create: '创建',
                                   copy: '复制',
                                   save: '保存',
                                   publish: '发布',
                                   disable: '停用'
                                 }[command.pending.request.kind])}
                                 action={<Button loading={command.busy} disabled={!!command.warning}
                                                 onClick={() => void command.submit()}>继续确认原操作</Button>}/>}
      {dirty && <Alert type="warning" title="有未保存的草稿修改"
                       action={<Popconfirm title="放弃本次未保存的修改？" okText="放弃修改" cancelText="取消"
                                           onConfirm={() => setEdit(null)}>
                         <Button disabled={command.busy}>放弃修改</Button>
                       </Popconfirm>}/>}
      <Flex gap={8} wrap align="center">
        <Button type="primary" disabled={disabled || dirty || !caps.value?.canCreate}
                onClick={() => select(undefined, true)}>新建助手</Button>
        <Button disabled={dirty || command.busy} onClick={() => {
          list.reload();
          caps.reload();
          detail.reload();
        }}>刷新管理列表和草稿</Button>
      </Flex>
      {!!caps.error && <Alert type="warning" title={assistantError(caps.error)}/>}
      <Flex wrap gap={8}>
        {list.loading && <Spin size="small"/>}
        {list.value?.items.map(item => <Button key={item.resource.id} disabled={dirty || command.busy}
                                               type={assistantId === item.resource.id ? 'primary' : 'default'}
                                               onClick={() => select(item.resource.id)}>
          {item.displayName || '未命名助手'} · {assistantStateLabel[item.resource.state]}
        </Button>)}
        {list.value?.items.length === 0 && <Typography.Text type="secondary">当前没有可管理的助手。</Typography.Text>}
        {!!list.error && <Alert type="warning" title={assistantError(list.error)}/>}
        {(cursor || list.value?.nextCursor) && <>
          <Button disabled={!cursor} onClick={() => setCursor(null)}>首页</Button>
          <Button disabled={!list.value?.nextCursor}
                  onClick={() => setCursor(list.value?.nextCursor ?? null)}>下一页</Button>
        </>}
      </Flex>
      {detail.loading && <Spin size="small"/>}
      {!!detail.error && <Alert type="warning" title={assistantError(detail.error)}/>}
      {creating ? <>
        <Flex gap={8} wrap>
          {templates.map(template => <Button key={template.displayName} disabled={disabled || dirty}
                                             onClick={() => setEdit(template)}>载入{template.displayName}示例</Button>)}
        </Flex>
        <ContentFields value={content} editable={!!canEdit && !disabled} onChange={setEdit}/>
        {validation && <Alert type="warning" title={validation}/>}
        <Button type="primary" disabled={!canEdit || disabled || !!validation} onClick={save}>创建草稿</Button>
      </> : saved ? <>
        <Flex gap={8} align="center" wrap>
          <Typography.Text strong>{saved.draft.content.displayName || '未命名助手'}</Typography.Text>
          <Tag>{assistantStateLabel[saved.resource.state]}</Tag>
          {saved.resource.releaseVersion !== null && <Tag>发布 v{saved.resource.releaseVersion}</Tag>}
          <Tag>草稿修订 {saved.draft.revision}</Tag>
          <Typography.Text
            type="secondary">更新于 {new Date(saved.draft.updatedAt).toLocaleString()} · {saved.draft.updatedBy}</Typography.Text>
          {caps.value?.canDisable &&
            <Popconfirm title="停用整个助手？" description="所有版本将停止接受新执行，已有会话历史仍可查看。"
                        okText="确认停用" cancelText="取消" onConfirm={() => void command.submit({
              kind: 'disable', scope, assistantId: saved.resource.id, expectedVersion: saved.resource.version,
            })}>
              <Button danger disabled={disabled || dirty}>停用助手</Button>
            </Popconfirm>}
        </Flex>
        <Tabs activeKey={tab} onChange={setTab} items={[
          {
            key: 'draft', label: '草稿', children: <Flex vertical gap={12}>
              <ContentFields value={content} editable={!!canEdit && !disabled} onChange={setEdit}/>
              {validation && <Alert type="warning" title={validation}/>}
              <Flex gap={8} wrap>
                {canEdit &&
                  <Button type="primary" disabled={disabled || !dirty || !!validation} onClick={save}>保存草稿</Button>}
                {caps.value?.canCreate && <Button disabled={disabled || dirty} onClick={() => void command.submit({
                  kind: 'copy', scope, sourceId: saved.resource.id, sourceDraftRevision: saved.draft.revision,
                })}>复制已保存草稿</Button>}
              </Flex>
            </Flex>
          },
          {
            key: 'trial', label: '试用与发布', children: tab === 'trial' && <AssistantTrialPanel
              key={saved.resource.id + ':' + saved.draft.revision} scope={scope} assistantId={saved.resource.id}
              revision={saved.draft.revision} complete={completeAssistant(saved.draft.content)}
              examples={saved.draft.content.examplePrompts}
              canTrial={!!caps.value?.canTrial} canPublish={!!caps.value?.canPublish}
              alreadyPublished={saved.publishedDraftRevision === saved.draft.revision} disabled={disabled || dirty}
              accepted={accepted?.assistantId === saved.resource.id ? accepted.trial : null}
              onTrial={value => void command.submit(value)} onPublish={trialId => void command.submit({
              kind: 'publish', scope, assistantId: saved.resource.id, expectedVersion: saved.resource.version,
              expectedRevision: saved.draft.revision, trialId,
            })}/>
          },
          {
            key: 'versions', label: '发布版本', children: <Flex vertical gap={10}>
              {versions.loading && <Spin size="small"/>}
              {!!versions.error && <Alert type="warning" title={assistantError(versions.error)}
                                          action={<Button size="small" onClick={versions.reload}>重试</Button>}/>}
              {versions.value?.items.length === 0 &&
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无发布版本"/>}
              <Flex gap={8} wrap>
                {versions.value?.items.map(item => <Button key={item.version}
                                                           type={selectedVersion === item.version ? 'primary' : 'default'}
                                                           onClick={() => setSelectedVersion(item.version)}>v{item.version}</Button>)}
                {(versionCursor || versions.value?.nextCursor) && <>
                  <Button disabled={!versionCursor} onClick={() => setVersionCursor(null)}>首页</Button>
                  <Button disabled={!versions.value?.nextCursor}
                          onClick={() => setVersionCursor(versions.value?.nextCursor ?? null)}>下一页</Button>
                </>}
              </Flex>
              {release && <>
                <Typography.Text>发布 v{release.version} ·
                  来源修订 {release.draftRevision} · {new Date(release.publishedAt).toLocaleString()} ·
                  发布者 {release.publishedBy}</Typography.Text>
                <ContentFields value={release.content} editable={false} onChange={() => {
                }}/>
                {caps.value?.canCreate && <Button disabled={disabled || dirty} onClick={() => void command.submit({
                  kind: 'copy', scope, sourceId: saved.resource.id, sourceVersion: release.version,
                })}>复制此发布版本</Button>}
              </>}
            </Flex>
          },
        ]}/>
      </> : !assistantId &&
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一个助手查看草稿，或新建助手。"/>}
    </Flex>
  </Drawer>;
}
