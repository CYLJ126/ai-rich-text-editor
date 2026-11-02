import React, {useCallback, useEffect, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Select, Tabs, Typography} from 'antd';
import PolicyEditor from '@/features/workspace/PolicyEditor';
import {protectWorkspaceChange, registerWorkspaceGuard, useWorkspaceDirectory} from '@/features/workspace/useWorkspace';
import {usePolicyDirectory} from '@/features/workspace/usePolicyDirectory';
import type {PolicySnapshot, TenantPolicyView} from '@/services/platform/policy';
import type {WorkspaceScope, WorkspaceView} from '@/services/platform/workspace';
import './style.css';

export default function PolicySettingsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理租户和空间政策。"/>;
  return <PolicySettings key={userId} userId={userId}/>;
}

export function PolicySettings({userId}: { userId: string }) {
  const directory = useWorkspaceDirectory(userId);
  const candidates = usePolicyDirectory(userId);
  const current = directory.current;
  const tenantId = current?.scope.tenantId;
  const [tab, setTab] = useState('tenant');
  const [tenantUnsafe, setTenantUnsafe] = useState(false);
  const [workspaceUnsafe, setWorkspaceUnsafe] = useState(false);
  const [guardMessage, setGuardMessage] = useState('');
  const [spaceRevision, setSpaceRevision] = useState(0);
  const [tenantView, setTenantView] = useState<TenantPolicyView | null>(null);
  const [tenantNames, setTenantNames] = useState<Record<string, string>>({});
  const unsafe = tenantUnsafe || workspaceUnsafe;
  const locked = unsafe || directory.selecting || directory.saving || !!directory.pending
    || directory.recoveryBlocked || directory.unsafeToLeave;
  const attempt = useRef(0);

  useEffect(() => () => {
    attempt.current++;
  }, []);
  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (!unsafe) {
      setGuardMessage('');
      proceed();
    } else setGuardMessage('政策有未保存草稿或待确认操作，请先保存、放弃修改或确认原操作后再切换。');
  }), [userId, unsafe]);
  useEffect(() => {
    setTenantView(null);
    setSpaceRevision(0);
  }, [tenantId]);

  const rememberTenant = useCallback((snapshot: PolicySnapshot | null) => {
    if (!snapshot) return;
    const id = snapshot.kind === 'tenant' ? snapshot.value.policy.tenantId : snapshot.value.policy.scope.tenantId;
    setTenantNames(names => ({...names, [id]: snapshot.value.tenantName}));
  }, []);
  const loadedTenant = useCallback((snapshot: PolicySnapshot | null) => {
    setTenantView(snapshot?.kind === 'tenant' ? snapshot.value : null);
    rememberTenant(snapshot);
  }, [rememberTenant]);
  const tenantSaved = useCallback(() => setSpaceRevision(value => value + 1), []);

  // 当前已验证空间可能尚未出现在已加载的目录页中，仍保留其选择；不伪造其他空间。
  const choices = new Map<string, WorkspaceView>();
  for (const item of candidates.records) choices.set(JSON.stringify([item.scope.tenantId, item.scope.workspaceId]), item);
  if (current) choices.set(JSON.stringify([current.scope.tenantId, current.scope.workspaceId]), current);
  const spaces = [...choices.values()];
  const tenantIds = [...new Set(spaces.map(item => item.scope.tenantId))];
  const tenantSpaces = spaces.filter(item => item.scope.tenantId === tenantId);
  const personalId = directory.preference?.defaultScope.tenantId;
  const tenantLabel = (id: string) => tenantNames[id]
    ? `${tenantNames[id]}（${id}）` : `${id === personalId ? '我的个人租户' : '租户'}（${id}）`;

  function select(scope: WorkspaceScope, personal = false) {
    if (locked) return;
    if (current?.scope.tenantId === scope.tenantId && current.scope.workspaceId === scope.workspaceId) {
      if (personal) setTab('tenant');
      return;
    }
    protectWorkspaceChange(userId, scope, () => {
      const selection = ++attempt.current;
      void directory.select(scope).then(opened => {
        if (opened && attempt.current === selection && personal) setTab('tenant');
      });
    });
  }

  function personal() {
    const target = directory.preference?.defaultScope;
    if (!target || locked) return;
    if (tenantId === target.tenantId) setTab('tenant');
    else select(target, true);
  }

  const managementDisabled = unsafe || directory.selecting || directory.unsafeToLeave;
  return <main className="workspace-policy-page" aria-label="租户与空间政策设置">
    <Flex component="header" justify="space-between" align="center" wrap gap={12}>
      <Typography.Title level={3}>租户配置</Typography.Title>
      <Flex gap={8} wrap>
        <Button disabled={locked || !directory.preference} onClick={personal}>我的个人租户政策</Button>
        <Button disabled={managementDisabled}
                onClick={() => history.push('/Environment/Workspaces')}>前往工作空间配置</Button>
        <Button disabled={managementDisabled} onClick={() => history.push('/Environment/Models')}>前往模型配置</Button>
      </Flex>
    </Flex>
    <Flex className="policy-scope-controls" gap={12} align="center" wrap>
      <Typography.Text>当前租户</Typography.Text>
      <Select aria-label="选择租户" placeholder="选择租户" value={tenantId}
              loading={candidates.loading || directory.selecting}
              disabled={locked} showSearch optionFilterProp="label"
              options={tenantIds.map(id => ({value: id, label: tenantLabel(id)}))}
              onChange={id => {
                const target = spaces.find(item => item.scope.tenantId === id);
                if (target) select(target.scope);
              }}/>
      <Button loading={candidates.loading || directory.loading} disabled={locked} onClick={() => {
        void directory.load();
        void candidates.reload();
      }}>刷新可选范围</Button>
      {candidates.hasMore && <Button disabled={locked || candidates.loading} onClick={() => void candidates.more()}>
        加载更多租户和空间
      </Button>}
    </Flex>
    <Typography.Paragraph type="secondary">
      可选租户来自当前账号可访问的工作空间。{!candidates.exhausted && '目录尚未加载完毕，未列出的租户或空间可继续加载。'}
    </Typography.Paragraph>
    {candidates.error && <Alert type="warning" title={candidates.expired
      ? '可选范围已过期，请刷新可选范围。' : candidates.error}/>}
    {directory.error != null && <Alert type="warning" title={directory.error instanceof Error
      ? directory.error.message : '当前空间读取失败，请刷新可选范围。'}/>}
    {directory.warning && <Alert type="warning" title={directory.warning}/>}
    {(directory.pending || directory.recoveryBlocked || directory.unsafeToLeave) &&
      <Alert type="warning" title="空间操作尚未确认或恢复记录不可用，请先在工作空间配置中处理原操作。"/>}
    <Typography.Paragraph>租户政策和空间政策分别按当前治理资格管理。修改政策不会自动分享模型或分配预算。</Typography.Paragraph>
    {(unsafe || guardMessage) && <Alert type="info"
                                        title={guardMessage || '当前有政策草稿或待确认操作，请先处理后再离开或切换租户、空间。'}/>}
    {!current && <Alert type="info" title="尚未选择可访问的租户。请从上方选择，或前往工作空间配置检查初始化及访问资格。"/>}
    {current && <Tabs activeKey={tab} onChange={setTab} destroyOnHidden={false} items={[
      {
        key: 'tenant', label: '租户政策（含个人）', forceRender: true,
        children: <PolicyEditor userId={userId} scope={{tenantId: current.scope.tenantId}} kind="tenant"
                                onUnsafeChange={setTenantUnsafe} onSaved={tenantSaved} onSnapshot={loadedTenant}/>,
      },
      {
        key: 'workspace', label: '空间政策', forceRender: true,
        children: <>
          <Flex className="policy-scope-controls" gap={12} align="center" wrap>
            <Typography.Text>工作空间</Typography.Text>
            <Select aria-label="选择政策工作空间" value={current.scope.workspaceId} disabled={locked}
                    loading={directory.selecting} showSearch optionFilterProp="label"
                    options={tenantSpaces.map(item => ({
                      value: item.scope.workspaceId,
                      label: `${item.name}（${item.scope.workspaceId}）`
                    }))}
                    onChange={id => {
                      const target = tenantSpaces.find(item => item.scope.workspaceId === id);
                      if (target) select(target.scope);
                    }}/>
          </Flex>
          <PolicyEditor userId={userId} scope={current.scope} kind="workspace" workspaceName={current.name}
                        onUnsafeChange={setWorkspaceUnsafe} ceiling={tenantView} refreshRevision={spaceRevision}
                        onSnapshot={rememberTenant}/>
        </>,
      },
    ]}/>}
  </main>;
}
