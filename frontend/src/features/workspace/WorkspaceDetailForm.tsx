import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Card, Descriptions, Flex, Form, Input, Select, Tabs, Typography} from 'antd';
import {
  checkWorkspaceAccess,
  getWorkspace,
  type WorkspaceScope,
  type WorkspaceView
} from '@/services/platform/workspace';
import {type AiModel, getWorkspaceAi} from '@/services/platform/ai';
import type {WorkspaceTab} from '@/features/configuration/configurationLocation';
import {configurationReadError} from '@/features/configuration/useConfigurationDirectory';
import WorkspaceAiGovernanceEditor from './WorkspaceAiGovernanceEditor';
import type {PolicyField} from '@/services/platform/policy';
import WorkspacePolicyPane from './WorkspacePolicyPane';
import {confirmedWorkspaceVersion, type WorkspaceVersionChange} from './workspaceVersionChange';
import {notifyAiConfiguration, subscribeAiConfiguration} from './aiConfigurationEvents';
import {sameScope} from '@/features/ainew/storage';
import {useWorkspaceManagement} from './useWorkspace';
import {AiApiError} from '@/services/arte-ai/request';
import {MembershipPanel, useMembershipCapabilities, useMembershipTarget} from './MembershipPanel';
import {useMembershipSession} from './MembershipSession';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';

interface Props {
  userId: string;
  scope?: WorkspaceScope;
  initialTenantId?: string;
  tenants?: { value: string; label: string }[];
  onMoreTenants?: () => void;
  onSaved?: (view: WorkspaceView) => void;
  onAccessLost?: () => void;
  onCancel?: () => void;
  onUnsafeChange?: (unsafe: boolean) => void;
  tab?: WorkspaceTab;
  onTabChange?: (tab: WorkspaceTab) => void;
  focusedModel?: AiModel;
  focusBudget?: boolean;
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
                  onAccessLost,
                  onCancel,
                  onUnsafeChange,
                  tab,
                  onTabChange,
                  focusedModel, focusBudget
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
  const [policyUnsafe, setPolicyUnsafe] = useState(false);
  const [policyFocus, setPolicyFocus] = useState<{ field: PolicyField }>();
  const [localTab, setLocalTab] = useState<WorkspaceTab>('overview');
  const [aiRevision, setAiRevision] = useState(0);
  const [policyRevision, setPolicyRevision] = useState(0);
  const [summaryRevision, setSummaryRevision] = useState(0);
  const [versionChange, setVersionChange] = useState<WorkspaceVersionChange>();
  const live = useRef(true);
  const reader = useRef<AbortController | null>(null);
  const [metadataChanged, setMetadataChanged] = useState(false);
  const ownNotification = useRef(false);
  const callbacks = useRef({onSaved, onAccessLost});
  callbacks.current = {onSaved, onAccessLost};
  const pending = directory.pending;
  const ownPending = pending?.kind === 'rename' ? sameScope(scope, pending.request.scope)
    : pending?.kind === 'create' && !scope;
  const dirty = scope ? !!view && (name !== view.name || description !== (view.description ?? ''))
    : !!name || !!description || tenantId !== initialTenantId;
  const locked = closed || loading || directory.saving || !!pending || directory.recoveryBlocked;
  const unsafe = !closed && (dirty || aiUnsafe || policyUnsafe || directory.saving || !!pending || directory.recoveryBlocked || directory.unsafeToLeave);
  const stableScope = useMemo(() => scope ? {...scope} : undefined, [scope?.tenantId, scope?.workspaceId]);
  const membership = useMembershipSession();
  const membershipRevision = membership?.revision ?? 0;
  const membershipTarget = useMembershipTarget(view?.scope.tenantId, view?.scope.workspaceId);
  const caps = useMembershipCapabilities(membershipTarget, view?.version);
  const context = useMemo(() => stableScope ? {userId, scope: stableScope} : undefined, [userId, stableScope]);
  useConfigurationDraft({
    group: 'workspace-metadata',
    label: scope ? '空间基本信息' : '新建空间信息',
    order: 10,
    status: directory.recoveryBlocked || directory.unsafeToLeave ? 'blocked' : directory.saving ? 'submitting' : pending ? 'pending' : dirty ? 'dirty' : 'clean',
    save: () => save(),
    discard: async () => {
      if (scope) return read();
      setName('');
      setDescription('');
      setTenantId(initialTenantId);
      return true;
    }
  });

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

  async function read(membershipRefresh = false) {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    setLoading(true);
    setError('');
    try {
      if (!stableScope) return true;
      const [{body}, permission] = await Promise.all([
        getWorkspace(stableScope, {signal: controller.signal}),
        checkWorkspaceAccess(stableScope, 'workspace', 'workspace:update', {signal: controller.signal})
          .catch(() => null),
      ]);
      if (!live.current || controller.signal.aborted) return false;
      if (!sameScope(body.data.scope, stableScope)) throw new Error('空间响应范围不一致。');
      setView(body.data);
      setAllowed(permission?.body.data.allowed === true);
      const original = directory.pending;
      const restored = original?.kind === 'rename' && sameScope(original.request.scope, stableScope) ? original.request : null;
      if (membershipRefresh && dirty && view
        && (view.name !== body.data.name || (view.description ?? '') !== (body.data.description ?? ''))) {
        setMetadataChanged(true);
        setNotice('空间信息已被更新，原草稿仍保留。请重新读取并核对后保存。');
      }
      if (!membershipRefresh || !dirty) {
        setMetadataChanged(false);
        setName(restored?.name ?? body.data.name);
        setDescription(restored?.description ?? body.data.description ?? '');
      }
      if (membershipRefresh) callbacks.current.onSaved?.(body.data);
      return true;
    } catch (failure) {
      if (live.current && !controller.signal.aborted) {
        setAllowed(false);
        if (membershipRefresh && failure instanceof AiApiError && [401, 403, 404].includes(failure.httpStatus)) {
          setView(undefined);
          callbacks.current.onAccessLost?.();
        }
        setError(configurationReadError(failure, '空间详情读取失败，请重新读取。'));
      }
      return false;
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

  const refreshRead = useRef(() => read(true));
  refreshRead.current = () => read(true);
  const lastMembershipRevision = useRef(membershipRevision);
  useEffect(() => {
    if (lastMembershipRevision.current !== membershipRevision && stableScope) {
      lastMembershipRevision.current = membershipRevision;
      void refreshRead.current();
    }
  }, [membershipRevision, stableScope]);

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
    setNotice('此空间配置已变化，当前草稿已保留。');
    setAiRevision(value => value + 1);
    setPolicyRevision(value => value + 1);
    setSummaryRevision(value => value + 1);
  }) : undefined, [userId, stableScope]);

  function governanceSaved(change?: WorkspaceVersionChange) {
    if (!stableScope) return;
    if (change) {
      setVersionChange(change);
      setView(value => value?.version === change.from ? {...value, version: change.to} : value);
    } else if (!dirty) void read();
    else setNotice('空间版本已更新，请重新读取空间信息后再保存。');
    setSummaryRevision(value => value + 1);
    ownNotification.current = true;
    notifyAiConfiguration(userId, stableScope);
    ownNotification.current = false;
  }

  useEffect(() => {
    if (versionChange && view?.version === versionChange.from) setView({...view, version: versionChange.to});
  }, [versionChange, view]);

  async function save(retry = false) {
    if (closed || loading || directory.saving || directory.recoveryBlocked) return false;
    if (!retry && (!allowed || !name.trim() || (!scope && !tenantId) || pending || directory.preferencePending || metadataChanged)) return false;
    const previousVersion = retry && pending?.kind === 'rename' ? pending.request.expectedVersion : view?.version;
    const saved = retry ? await directory.retry()
      : view ? await directory.rename(view, name.trim(), description.trim())
        : await directory.create(name.trim(), description.trim(), tenantId);
    if (!live.current || !saved) return false;
    const updated = directory.savedWorkspace();
    if (!updated || (scope ? !sameScope(updated.scope, scope) : updated.scope.tenantId !== tenantId)) {
      setError('空间保存回执与目标不一致，请确认原操作。');
      return false;
    }
    setMetadataChanged(false);
    setView(updated);
    setName(updated.name);
    setDescription(updated.description ?? '');
    if (scope) setVersionChange(confirmedWorkspaceVersion(previousVersion, updated.version));
    setNotice(scope ? '空间信息已保存。' : '工作空间已创建。');
    callbacks.current.onSaved?.(updated);
    return true;
  }

  const fields = <Form component="div" layout="vertical" disabled={locked || !allowed}>
    {!scope && <Form.Item label="所属租户" required>
      <Select aria-label="新空间所属租户" value={tenantId || undefined}
              options={tenants} placeholder="请选择所属租户"
              onChange={setTenantId} disabled={locked || !!name || !!description}/>
    </Form.Item>}
    <Form.Item label="空间名称" required><Input aria-label="空间名称" value={name} maxLength={100}
                                                onChange={event => setName(event.target.value)}/></Form.Item>
    <Form.Item label="空间描述"><Input.TextArea aria-label="空间描述" value={description} maxLength={1000}
                                                onChange={event => setDescription(event.target.value)}/></Form.Item>
    <Flex gap={8} wrap>
      <Button type="primary"
              disabled={locked || !allowed || !name.trim() || (!!scope && !dirty) || !!directory.preferencePending || metadataChanged}
              loading={directory.saving} onClick={() => void save()}>{scope ? '保存空间信息' : '创建工作空间'}</Button>
      {scope && <Button disabled={locked}
                        onClick={() => void read()}>{dirty ? '放弃修改并重新读取空间信息' : '重新读取空间信息'}</Button>}
      {!scope && onCancel && <Button disabled={locked} onClick={onCancel}>取消新增</Button>}
    </Flex>
  </Form>;

  return <Flex component="section" vertical gap={12} className="ai-management-panel"
               aria-label={scope ? '工作空间详情表单' : '新建工作空间表单'}>
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
    {!scope ? <>
      <Typography.Title level={4}>新建工作空间</Typography.Title>
      {fields}
      {onMoreTenants && <Button disabled={locked} onClick={onMoreTenants}>加载更多租户</Button>}
      {tenantId && !allowed && !locked &&
        <Typography.Text type="secondary">请选择有创建工作空间资格的租户。</Typography.Text>}
    </> : view && context && !closed ? <Tabs
      activeKey={(tab ?? localTab) === 'members' && !caps?.manage && !caps?.leave ? 'overview' : (tab ?? localTab)}
      destroyOnHidden={false}
                                             onChange={key => (onTabChange ?? setLocalTab)(key as WorkspaceTab)}
                                             items={[
                                               {
                                                 key: 'overview', label: '概览', children: <Flex vertical gap={12}>
                                                   <Descriptions size="small" column={{xs: 1, sm: 2}} items={[
                                                     {
                                                       key: 'role',
                                                       label: '我的角色',
                                                       children: ['负责人', '管理员', '成员', '访客'][view.currentRole]
                                                     },
                                                     {
                                                       key: 'state',
                                                       label: '空间状态',
                                                       children: ['正常', '已归档', '关闭处理中', '已删除'][view.state]
                                                     },
                                                   ]}/>
                                                   <Card title="空间信息" size="small">
                                                     {fields}
                                                     {!allowed && <Typography.Text
                                                       type="secondary">当前没有修改此空间信息的权限。</Typography.Text>}
                                                   </Card>
                                                   <WorkspaceAiSummary scope={context.scope}
                                                                       revision={summaryRevision}/>
                                                 </Flex>
                                               },
                                               {
                                                 key: 'ai',
                                                 label: 'AI 配置',
                                                 children: <WorkspaceAiGovernanceEditor scope={context.scope}
                                                                                        context={context}
                                                                                        focusedModel={focusedModel}
                                                                                        focusBudget={focusBudget}
                                                                                        onPolicyField={field => {
                                                                                          setPolicyFocus({field});
                                                                                          (onTabChange ?? setLocalTab)('policy');
                                                                                        }}
                                                                                        versionChange={versionChange}
                                                                                        refreshRevision={aiRevision}
                                                                                        onUnsafeChange={setAiUnsafe}
                                                                                        onUpdated={governanceSaved}/>
                                               },
                                               ...(caps && (caps.manage || caps.leave) && membershipTarget ? [{
                                                 key: 'members', label: '成员',
                                                 children: <MembershipPanel target={membershipTarget}
                                                                            version={view.version} caps={caps}
                                                                            disabled={locked}/>
                                               }] : []),
                                               {
                                                 key: 'policy',
                                                 label: '政策',
                                                 children: <WorkspacePolicyPane userId={userId} view={view}
                                                                                focus={policyFocus}
                                                                                revision={policyRevision}
                                                                                versionChange={versionChange}
                                                                                onSaved={governanceSaved}
                                                                                onUnsafeChange={setPolicyUnsafe}/>
                                               },
                                             ]}/> : <>{fields}{loading &&
      <Typography.Text role="status">正在读取空间详情…</Typography.Text>}</>}
  </Flex>;
}

function WorkspaceAiSummary({scope, revision}: { scope: WorkspaceScope; revision: number }) {
  const [summary, setSummary] = useState('正在读取 AI 摘要…');
  useEffect(() => {
    const request = new AbortController();
    setSummary('正在读取 AI 摘要…');
    void getWorkspaceAi(scope, {signal: request.signal}).then(({body: {data}}) => {
      if (request.signal.aborted) return;
      if (data.mode !== 'DATABASE' || (data.configuration && !sameScope(data.configuration.scope, scope))) {
        throw new Error('AI 配置目标不一致');
      }
      const current = data.configuration;
      setSummary(current ? `${current.enabled ? '已启用' : '未启用'} · 已配置 ${current.models.length} 个模型` : '尚未准备空间 AI 配置。');
    }).catch(failure => {
      if (!request.signal.aborted) setSummary(configurationReadError(failure, '空间 AI 摘要暂不可读。'));
    });
    return () => request.abort();
  }, [scope, revision]);

  return <Card title="AI 摘要" size="small"><Typography.Text>{summary}</Typography.Text></Card>;
}
