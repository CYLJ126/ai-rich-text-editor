import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Flex, Form, Input, Select, Switch, Typography} from 'antd';
import {
  checkWorkspaceAccess,
  getWorkspace,
  type WorkspaceScope,
  type WorkspaceView
} from '@/services/platform/workspace';
import WorkspaceAiGovernanceEditor from '@/features/ainew/WorkspaceAiGovernanceEditor';
import {notifyAiConfiguration, subscribeAiConfiguration} from '@/features/ainew/aiConfigurationEvents';
import {sameScope} from '@/features/ainew/storage';
import {useWorkspaceManagement} from './useWorkspace';

interface Props {
  userId: string;
  scope?: WorkspaceScope;
  initialTenantId?: string;
  tenants?: { value: string; label: string }[];
  onMoreTenants?: () => void;
  onSaved?: (view: WorkspaceView) => void;
  onCancel?: () => void;
  onUnsafeChange?: (unsafe: boolean) => void;
}

/** 目标由调用方提供；编辑与新增均不选择聊天空间。换目标时卸载旧草稿与读取。 */
export default function WorkspaceDetailForm(props: Props) {
  return <Detail key={JSON.stringify([props.userId, props.scope?.tenantId, props.scope?.workspaceId])} {...props}/>;
}

function Detail({
                  userId,
                  scope,
                  initialTenantId = '',
                  tenants = [],
                  onMoreTenants,
                  onSaved,
                  onCancel,
                  onUnsafeChange
                }: Props) {
  const directory = useWorkspaceManagement(userId);
  const [view, setView] = useState<WorkspaceView>();
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [allowed, setAllowed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [closed, setClosed] = useState(false);
  const [aiUnsafe, setAiUnsafe] = useState(false);
  const [aiRevision, setAiRevision] = useState(0);
  const [metadataRevision, setMetadataRevision] = useState(0);
  const live = useRef(true);
  const reader = useRef<AbortController | null>(null);
  const ownNotification = useRef(false);
  const callbacks = useRef({onSaved});
  callbacks.current = {onSaved};
  const pending = directory.pending;
  const ownPending = pending?.kind === 'rename' ? sameScope(scope, pending.request.scope)
    : pending?.kind === 'create' && !scope;
  const dirty = scope ? !!view && (name !== view.name || description !== (view.description ?? ''))
    : !!name || !!description || tenantId !== initialTenantId;
  const locked = closed || loading || directory.saving || !!pending || directory.recoveryBlocked;
  const unsafe = !closed && (dirty || aiUnsafe || directory.saving || !!pending || directory.recoveryBlocked || directory.unsafeToLeave);
  const stableScope = useMemo(() => scope ? {...scope} : undefined, [scope?.tenantId, scope?.workspaceId]);
  const context = useMemo(() => stableScope ? {userId, scope: stableScope} : undefined, [userId, stableScope]);

  useEffect(() => {
    onUnsafeChange?.(unsafe);
  }, [unsafe, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (unsafe) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unsafe]);

  async function read() {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    setLoading(true);
    setError('');
    try {
      if (!stableScope) return;
      const [{body}, permission] = await Promise.all([
        getWorkspace(stableScope, {signal: controller.signal}),
        checkWorkspaceAccess(stableScope, 'workspace', 'workspace:update', {signal: controller.signal})
          .catch(() => null),
      ]);
      if (!live.current || controller.signal.aborted) return;
      if (!sameScope(body.data.scope, stableScope)) throw new Error('空间响应范围不一致。');
      setView(body.data);
      setAllowed(permission?.body.data.allowed === true);
      const original = directory.pending;
      const restored = original?.kind === 'rename' && sameScope(original.request.scope, stableScope) ? original.request : null;
      setName(restored?.name ?? body.data.name);
      setDescription(restored?.description ?? body.data.description ?? '');
    } catch (failure) {
      if (live.current && !controller.signal.aborted) setError(failure instanceof Error ? failure.message : '空间详情读取失败。');
    } finally {
      if (live.current && !controller.signal.aborted) setLoading(false);
    }
  }

  useEffect(() => {
    live.current = true;
    const original = directory.pending;
    if (!stableScope && original?.kind === 'create') {
      setTenantId(original.request.tenantId);
      setName(original.request.name);
      setDescription(original.request.description);
    }
    void read();
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      live.current = false;
      reader.current?.abort();
      setClosed(true);
      setView(undefined);
      setName('');
      setDescription('');
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      reader.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [stableScope, userId]);

  useEffect(() => {
    if (stableScope) return;
    const controller = new AbortController();
    setAllowed(false);
    if (tenantId) void checkWorkspaceAccess({tenantId}, 'tenant', 'tenant:workspace:create', {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted && live.current) setAllowed(body.data.allowed);
      })
      .catch(() => {
        if (!controller.signal.aborted) setAllowed(false);
      });
    return () => controller.abort();
  }, [tenantId, stableScope]);

  useEffect(() => stableScope ? subscribeAiConfiguration(userId, stableScope, () => {
    if (ownNotification.current) return;
    setNotice('此空间配置已变化，请保存或放弃当前修改后重新读取。');
    setAiRevision(value => value + 1);
  }) : undefined, [userId, stableScope]);

  async function save(retry = false) {
    if (closed || loading || directory.saving || directory.recoveryBlocked || aiUnsafe) return;
    if (!retry && (!allowed || !name.trim() || (!scope && !tenantId) || pending)) return;
    const saved = retry ? await directory.retry()
      : view ? await directory.rename(view, name.trim(), description.trim())
        : await directory.create(name.trim(), description.trim(), tenantId);
    if (!live.current || !saved) return;
    const updated = directory.savedWorkspace();
    if (!updated || (scope ? !sameScope(updated.scope, scope) : updated.scope.tenantId !== tenantId)) {
      setError('空间保存回执与目标不一致，请确认原操作。');
      return;
    }
    setView(updated);
    setName(updated.name);
    setDescription(updated.description ?? '');
    setMetadataRevision(value => value + 1);
    setNotice('空间信息已保存。');
    ownNotification.current = true;
    notifyAiConfiguration(userId, updated.scope);
    ownNotification.current = false;
    callbacks.current.onSaved?.(updated);
  }

  return <Flex vertical gap={12} className="ai-management-panel" aria-label="工作空间详情表单">
    {closed && <Alert type="warning" title="账号会话已结束，请重新登录。"/>}
    {error && <Alert type="warning" title={error}/>}
    {notice && <Alert type="info" title={notice}/>}
    {directory.warning && <Alert type="warning" title={directory.warning}/>}
    {directory.error != null && <Alert type="warning"
                                       title={directory.error instanceof Error ? directory.error.message : '空间操作失败，请重试。'}/>}
    {pending && !ownPending && <Alert type="warning" title="另一个空间操作待确认，请在工作空间配置页处理原操作。"/>}
    {ownPending && <Alert type="warning" title="空间信息保存结果待确认，请使用原操作重试。"
                          action={<Button aria-label="重试确认原空间信息操作" loading={directory.saving}
                                          disabled={directory.recoveryBlocked || closed}
                                          onClick={() => void save(true)}>重试确认原空间信息操作</Button>}/>}
    {view &&
      <Typography.Text type="secondary">租户：{view.scope.tenantId} · 空间：{view.scope.workspaceId}</Typography.Text>}
    <Form component="div" layout="vertical" disabled={locked || aiUnsafe || !allowed}>
      {!scope && <Form.Item label="所属租户" required><Select aria-label="新空间所属租户" value={tenantId || undefined}
                                                              options={tenants} placeholder="请选择有创建资格的租户"
                                                              onChange={setTenantId}
                                                              disabled={locked || !!name || !!description}
                                                              popupRender={menu => <>{menu}{onMoreTenants &&
                                                                <Button
                                                                  onClick={onMoreTenants}>加载更多租户</Button>}</>}/></Form.Item>}
      <Form.Item label="空间名称" required><Input aria-label="空间名称" value={name} maxLength={100}
                                                  onChange={event => setName(event.target.value)}/></Form.Item>
      <Form.Item label="空间描述"><Input.TextArea aria-label="空间描述" value={description} maxLength={1000}
                                                  onChange={event => setDescription(event.target.value)}/></Form.Item>
      <Flex gap={8} wrap>
        <Button type="primary" disabled={locked || aiUnsafe || !allowed || !name.trim() || (!!scope && !dirty)}
                loading={directory.saving} onClick={() => void save()}>保存空间信息</Button>
        {scope && <Button disabled={locked || aiUnsafe} onClick={() => void read()}>放弃修改并重新读取空间信息</Button>}
        {!scope && onCancel && <Button disabled={locked} onClick={onCancel}>取消新增</Button>}
      </Flex>
    </Form>
    {scope && view && !allowed && <Typography.Text type="secondary">当前没有修改此空间信息的权限。</Typography.Text>}
    {!scope && <>
      <Alert type="info" title="保存空间信息后，才能准备并编辑下方 AI 配置。"/>
      <Form component="div" layout="vertical" disabled>
        <Form.Item label="启用此空间 AI"><Switch aria-label="启用此空间 AI"/></Form.Item>
        <Form.Item label="空间可用模型"><Select aria-label="空间可用模型" mode="multiple"/></Form.Item>
        <Form.Item label="独立预算费率"><Select aria-label="独立预算费率" mode="multiple"/></Form.Item>
      </Form>
    </>}
    {view && context && !closed &&
      <WorkspaceAiGovernanceEditor key={metadataRevision} scope={context.scope} context={context}
                                   disabled={dirty || locked} refreshRevision={aiRevision} onUnsafeChange={setAiUnsafe}
                                   onUpdated={() => {
                                     ownNotification.current = true;
                                     notifyAiConfiguration(userId, context.scope);
                                     ownNotification.current = false;
                                     // AI 操作也会增加空间版本；刷新名称表单的并发版本，不重建 AI 草稿。
                                     void read();
                                   }}/>}
  </Flex>;
}
