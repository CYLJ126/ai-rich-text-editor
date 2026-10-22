import React, {useEffect, useId, useRef, useState} from 'react';
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
  Tag,
  Typography
} from 'antd';
import {AiApiError} from '@/services/arte-ai';
import {sameScope} from '@/features/ainew/storage';
import {protectWorkspaceChange, useWorkspaceDirectory} from './useWorkspace';
import {getWorkspace, type WorkspaceScope, type WorkspaceView} from '@/services/platform/workspace';
import '../aiManagement.css';

export default function WorkspacePicker({
                                          userId,
                                          beforeSelect,
                                          open: controlledOpen,
                                          onOpenChange,
                                          presentation = 'drawer'
                                        }: {
  userId: string;
  presentation?: 'drawer' | 'inline';
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  beforeSelect?: (scope: WorkspaceScope, proceed: () => void) => void;
}) {
  const directory = useWorkspaceDirectory(userId);
  const formId = useId();
  const [internalOpen, setInternalOpen] = useState(false);
  const open = controlledOpen ?? internalOpen;

  function setOpen(value: boolean) {
    setInternalOpen(value);
    onOpenChange?.(value);
  }

  const [filter, setFilter] = useState('');
  useEffect(() => setFilter(directory.search), [directory.search]);
  const [editor, setEditor] = useState<{ kind: 'create'; tenantId: string } | {
    kind: 'rename';
    view: WorkspaceView
  } | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const [editorError, setEditorError] = useState<string | null>(null);
  const detailRead = useRef<AbortController | null>(null);
  useEffect(() => () => detailRead.current?.abort(), []);
  useEffect(() => {
    if (editor?.kind !== 'rename' || directory.pending || !sameScope(editor.view.scope, directory.current?.scope)
      || editor.view.version === directory.current?.version) return;
    // Refresh the concurrency token, while retaining the user's name/description for explicit review and resubmission.
    setEditor({kind: 'rename', view: directory.current!});
  }, [directory.current, directory.pending, editor]);
  const role = ['负责人', '管理员', '成员', '访客'];
  const state = ['正常', '已归档', '关闭处理中', '已删除'];
  const locked = directory.saving || !!directory.pending || directory.recoveryBlocked;
  // A denied target does not revoke access to the currently validated space or the rest of the directory.
  const unavailable = directory.error instanceof AiApiError && (directory.error.httpStatus === 401
    || (directory.error.httpStatus === 403 && !directory.current));

  function protect(scope: WorkspaceScope, proceed: () => void) {
    if (beforeSelect) beforeSelect(scope, proceed);
    else protectWorkspaceChange(userId, scope, proceed);
  }

  async function editCurrent() {
    if (!directory.current) return;
    detailRead.current?.abort();
    const controller = new AbortController();
    detailRead.current = controller;
    const scope = directory.current.scope;
    setDetailLoading(true);
    setEditorError(null);
    try {
      const response = await getWorkspace(scope, {signal: controller.signal});
      if (controller.signal.aborted) return;
      if (!sameScope(response.body.data.scope, scope)) throw new Error('空间响应范围不一致，请刷新后重试。');
      const view = response.body.data;
      setEditor({kind: 'rename', view});
      setName(view.name);
      setDescription(view.description ?? '');
    } catch (failure) {
      if (!controller.signal.aborted) setEditorError(failure instanceof Error ? failure.message : '空间详情读取失败。');
    } finally {
      if (!controller.signal.aborted) setDetailLoading(false);
    }
  }

  const visible = directory.records;
  const error = directory.error instanceof Error ? directory.error.message : '空间读取失败，请重试。';
  // 工作空间辅助栏 Tab 页主体内容
  const panel = <Flex vertical gap={12} className="ai-management-panel">
    {directory.warning && <Alert type="warning" title={directory.warning}/>}
    {directory.error != null && <Alert type="error" title={error}/>}
    {editorError && <Alert type="error" title={editorError}/>}
    <Button
      loading={directory.loading}
      disabled={directory.saving}
      onClick={() => void directory.load(filter)}>刷新空间目录</Button>

    {!directory.preference
      && <Button
        loading={directory.saving}
        disabled={locked || unavailable}
        onClick={() => void directory.initialize()}>初始化个人默认空间</Button>}

    {directory.pending
      && <Alert
        type="warning"
        title="空间操作结果待确认"
        description={`操作：${({
          initialize: '个人初始化',
          create: '创建空间',
          rename: '空间改名',
          preference: '保存偏好'
        })[directory.pending.kind]}。重试保持原目标、参数和操作键，原表单暂时锁定。`}
        action={<Button
          loading={directory.saving} disabled={directory.recoveryBlocked}
          onClick={() => protect(directory.current?.scope ?? directory.preference?.defaultScope ?? {
            tenantId: '',
            workspaceId: ''
          }, () => void directory.retry())}>重试确认原操作</Button>}/>}

    {directory.current
      && <Flex component="section" vertical gap={8} align="flex-start" aria-label="当前空间信息">
        <Typography.Text strong>当前空间：{directory.current.name}</Typography.Text>
        <Descriptions column={1} size="small" className="ai-management-info" items={[
          {key: 'tenant', label: '所属租户', children: directory.current.scope.tenantId},
          {key: 'description', label: '描述', children: directory.current.description || '暂无描述'},
        ]}/>
        {directory.canRename &&
          <Button disabled={locked} loading={detailLoading} onClick={() => void editCurrent()}>修改空间信息</Button>}
      </Flex>}

    <Input.Search
      aria-label="搜索工作空间" placeholder="搜索工作空间" value={filter}
      maxLength={100} allowClear enterButton="搜索" disabled={directory.saving}
      onChange={event => setFilter(event.target.value)}
      onSearch={value => void directory.setSearch(value)}/>

    {/* 空间列表 */}
    <Flex component="ul" vertical gap={12} className="ai-management-list">
      {visible.map(item => <Flex component="li" vertical key={JSON.stringify(item.scope)}>
        <Card size="small" styles={{body: {padding: 12}}}>
          <Flex vertical gap={8} align="flex-start">
            <Button className="workspace-picker-name"
                    type={sameScope(item.scope, directory.current?.scope) ? 'primary' : 'default'}
                    disabled={directory.selecting || directory.unsafeToLeave || directory.recoveryBlocked || unavailable}
                    onClick={() => protect(item.scope, () => {
                      void directory.select(item.scope).then(selected => {
                        if (selected) setOpen(false);
                      });
                    })}>{item.name}</Button>
            <Descriptions column={1} size="small" className="ai-management-info" items={[
              {key: 'tenant', label: '所属租户', children: item.scope.tenantId},
            ]}/>
            <Flex gap={4}
                  wrap><Tag>{role[item.currentRole]}</Tag><Tag>{state[item.state]}</Tag>{item.defaultWorkspace &&
              <Tag>个人默认空间</Tag>}
              {directory.favorite(item.scope) && <Tag>已收藏</Tag>}</Flex>
            <Flex gap={8} wrap>
              <Button size="small" disabled={!directory.preference || locked}
                      onClick={() => void directory.updatePreference({
                        favorites: directory.favorite(item.scope)
                          ? directory.preference!.favorites.filter(scope => !sameScope(scope, item.scope))
                          : [...directory.preference!.favorites, item.scope]
                      })}>{directory.favorite(item.scope) ? '取消收藏' : '收藏'}</Button>
              <Button size="small" disabled={!directory.preference || locked}
                      onClick={() => void directory.updatePreference({startupScope: item.scope})}>{sameScope(item.scope, directory.preference?.startupScope) ? '已设为启动空间' : '设为启动空间'}</Button>
            </Flex>
          </Flex>
        </Card>
      </Flex>)}
    </Flex>

    {!directory.loading && !directory.error && !visible.length &&
      <Empty description={directory.exhausted ? '没有匹配的工作空间。' : '暂未找到匹配空间，可以继续查找。'}/>}
    {!directory.exhausted && directory.nextCursor && !directory.cursorExpired &&
      <Button loading={directory.loading} disabled={directory.saving || directory.loading}
              onClick={() => void directory.loadMore()}>{visible.length ? '继续加载' : '继续查找'}</Button>}
    {directory.cursorExpired &&
      <Button disabled={directory.saving} onClick={() => void directory.load(filter)}>重新加载目录</Button>}
    {directory.preference?.startupScope
      && <Button
        disabled={locked}
        onClick={() => void directory.updatePreference({startupScope: null})}>清除启动空间偏好</Button>}

    {directory.canCreate && directory.current && <Button disabled={locked} onClick={() => {
      setEditor({kind: 'create', tenantId: directory.current!.scope.tenantId});
      setName('');
      setDescription('');
      setEditorError(null);
    }}>新建工作空间</Button>}

    {directory.lastReceipt && <><Typography.Text role="status">空间操作已保存。</Typography.Text>
      <Collapse
        ghost size="small" styles={{header: {padding: '8px 0'}, body: {padding: '0 0 8px'}}}
        items={[{
          key: 'receipt', label: '查看操作回执', children:
            <Descriptions column={1} size="small" className="ai-management-info" items={[
              {key: 'operation', label: '操作编号', children: directory.lastReceipt.operationId},
            ]}/>
        }]}/>
    </>}
    <Typography.Paragraph>邀请、成员管理及空间关闭将在对应能力接入后开放。模型和预算在进入聊天后检查。</Typography.Paragraph>
  </Flex>;
  return <>
    {presentation === 'drawer' &&
      <Button onClick={() => setOpen(true)} loading={directory.loading && !directory.current}
              aria-label="选择工作空间" className="workspace-picker-trigger" style={{maxWidth: 'min(280px, 70vw)'}}>
        <Typography.Text ellipsis title={directory.current?.name}>
          {directory.current?.name ?? '选择工作空间'}</Typography.Text> ▾</Button>}
    {presentation === 'inline' ?
      <Flex component="section" vertical className="workspace-inline" aria-label="工作空间管理">{panel}</Flex>
      : <Drawer title="工作空间" open={open} onClose={() => setOpen(false)}>{panel}</Drawer>}

    <Modal title={editor?.kind === 'rename' ? '修改空间信息' : '新建工作空间'} open={!!editor} okText="保存"
           cancelText="取消"
           onCancel={() => {
             if (!directory.saving) setEditor(null);
           }} confirmLoading={directory.saving}
           okButtonProps={{disabled: !name.trim() || locked}} onOk={() => {
      if (!editor) return;
      const target = editor;
      const submit = async () => {
        const saved = target.kind === 'rename'
          ? await directory.rename(target.view, name.trim(), description.trim())
          : await directory.create(name.trim(), description.trim(), target.tenantId);
        if (saved) {
          setEditor(null);
          setEditorError(null);
        }
      };
      if (target.kind === 'create' && directory.current) protect(directory.current.scope, () => void submit());
      else void submit();
    }}>
      <Typography.Paragraph
        style={{overflowWrap: 'anywhere'}}>保存到租户：{editor?.kind === 'create' ? editor.tenantId : editor?.view.scope.tenantId}。
        {editor?.kind === 'create' ? '创建空间不需要先配置模型或预算。' : '只修改名称与描述，不改变归属。'}</Typography.Paragraph>
      {directory.error != null && <Alert type="error" title={error}/>}
      {directory.pending && <Alert type="warning" title="原操作结果待确认，表单内容保留。"
                                   action={<Button loading={directory.saving} disabled={directory.recoveryBlocked}
                                                   onClick={() => {
                                                     const retry = () => {
                                                       void directory.retry().then(saved => {
                                                         if (saved) setEditor(null);
                                                       });
                                                     };
                                                     if (directory.pending?.kind === 'create' && directory.current) protect(directory.current.scope, retry);
                                                     else retry();
                                                   }}>重试确认原操作</Button>}/>}
      <Form component="div" layout="vertical" className="ai-management-form">
        <Form.Item label="空间名称" htmlFor={`${formId}-name`} required>
          <Input id={`${formId}-name`} aria-label="空间名称" placeholder="空间名称" maxLength={100} disabled={locked}
                 value={name}
                 onChange={event => setName(event.target.value)}/>
        </Form.Item>
        <Form.Item label="空间描述" htmlFor={`${formId}-description`}>
          <Input.TextArea id={`${formId}-description`} aria-label="空间描述" placeholder="描述（可选）" maxLength={1000}
                          disabled={locked}
                          value={description}
                          onChange={event => setDescription(event.target.value)}/>
        </Form.Item>
      </Form>
    </Modal>
  </>;
}
