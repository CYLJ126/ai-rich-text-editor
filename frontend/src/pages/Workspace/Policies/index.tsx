import React, {useCallback, useEffect, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Select, Tabs, Typography} from 'antd';
import PolicyEditor from '@/features/workspace/PolicyEditor';
import {useCurrentWorkspace, useWorkspaceManagement} from '@/features/workspace/useWorkspace';
import {usePolicyDirectory} from '@/features/workspace/usePolicyDirectory';
import type {PolicySnapshot, TenantPolicyView} from '@/services/platform/policy';
import {getWorkspace, type WorkspaceScope, type WorkspaceView} from '@/services/platform/workspace';
import {sameScope} from '@/features/ainew/storage';
import ConfigurationBoundary, {
  useConfigurationFallback,
  useConfigurationNavigation
} from '@/features/configuration/ConfigurationBoundary';
import ConfigurationTarget from '@/features/configuration/ConfigurationTarget';
import './style.css';

export default function PolicySettingsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理租户和空间政策。"/>;
  return <PolicySettings key={userId} userId={userId}/>;
}

export function PolicySettings({userId}: { userId: string }) {
  return <ConfigurationBoundary userId={userId}><PolicyConfiguration userId={userId}/></ConfigurationBoundary>;
}

function PolicyConfiguration({userId}: { userId: string }) {
  const directory = useWorkspaceManagement(userId);
  const candidates = usePolicyDirectory(userId);
  const chat = useCurrentWorkspace(userId);
  const [current, setCurrent] = useState<WorkspaceView | null>(chat);
  const [selecting, setSelecting] = useState(false);
  const [selectionError, setSelectionError] = useState('');
  const [closed, setClosed] = useState(false);
  const navigation = useConfigurationNavigation();
  const tenantId = current?.scope.tenantId;
  const [tab, setTab] = useState('tenant');
  const [tenantUnsafe, setTenantUnsafe] = useState(false);
  const [workspaceUnsafe, setWorkspaceUnsafe] = useState(false);
  const [spaceRevision, setSpaceRevision] = useState(0);
  const [tenantView, setTenantView] = useState<TenantPolicyView | null>(null);
  const [tenantNames, setTenantNames] = useState<Record<string, string>>({});
  const unsafe = tenantUnsafe || workspaceUnsafe;
  const locked = selecting;
  const attempt = useRef(0);
  const reader = useRef<AbortController | null>(null);
  useConfigurationFallback(tenantUnsafe, 'tenant-policy', '租户政策');
  useConfigurationFallback(workspaceUnsafe, 'space-policy', '空间政策');

  useEffect(() => () => {
    attempt.current++;
    reader.current?.abort();
  }, []);
  useEffect(() => {
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      attempt.current++;
      reader.current?.abort();
      setCurrent(null);
      setClosed(true);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => window.removeEventListener('arte-account-session-cleared', logout);
  }, [userId]);
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
    navigation.request(() => {
      const selection = ++attempt.current;
      reader.current?.abort();
      const controller = new AbortController();
      reader.current = controller;
      setSelecting(true);
      setSelectionError('');
      void getWorkspace(scope, {signal: controller.signal}).then(({body}) => {
        if (controller.signal.aborted || attempt.current !== selection) return;
        if (!sameScope(body.data.scope, scope)) throw new Error('配置目标响应不一致。');
        navigation.request(() => {
          if (controller.signal.aborted || attempt.current !== selection) return;
          setCurrent(body.data);
          if (personal) setTab('tenant');
        }, current?.scope.tenantId === scope.tenantId ? ['space-policy'] : undefined);
      }).catch(() => {
        if (!controller.signal.aborted && attempt.current === selection) setSelectionError('所选配置目标不可读取，请刷新可选范围后重试。');
      }).finally(() => {
        if (!controller.signal.aborted && attempt.current === selection) setSelecting(false);
      });
    }, current?.scope.tenantId === scope.tenantId ? ['space-policy'] : undefined);
  }

  function personal() {
    const target = directory.preference?.defaultScope;
    if (!target || locked) return;
    if (tenantId === target.tenantId) setTab('tenant');
    else select(target, true);
  }

  const managementDisabled = selecting;
  if (closed) return <Alert type="warning" title="账号会话已结束，请重新登录后打开租户配置。"/>;
  return <main className="workspace-policy-page" aria-label="租户与空间政策设置">
    <Flex component="header" justify="space-between" align="center" wrap gap={12}>
      <Typography.Title level={3}>租户配置</Typography.Title>
      <Flex gap={8} wrap>
        <Button disabled={locked || !directory.preference} onClick={personal}>我的个人租户政策</Button>
        <Button disabled={managementDisabled}
                onClick={() => navigation.request(() => history.push('/Environment/Workspaces'))}>前往工作空间配置</Button>
        <Button disabled={managementDisabled}
                onClick={() => navigation.request(() => history.push('/Environment/Models'))}>前往模型配置</Button>
      </Flex>
    </Flex>
    <ConfigurationTarget
      name={current ? (tab === 'tenant' ? tenantNames[current.scope.tenantId] ?? tenantLabel(current.scope.tenantId) : current.name) : undefined}
      scope={current ? (tab === 'tenant' ? {tenantId: current.scope.tenantId} : current.scope) : undefined}
      purpose={tab === 'tenant' ? '租户政策' : '空间政策'}/>
    <Flex className="policy-scope-controls" gap={12} align="center" wrap>
      <Typography.Text>当前租户</Typography.Text>
      <Select aria-label="选择租户" placeholder="选择租户" value={tenantId}
              loading={candidates.loading || selecting}
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
      当前列表仅包含有可访问工作空间的租户。{!candidates.exhausted && '目录尚未加载完毕，未列出的租户或空间可继续加载。'}
    </Typography.Paragraph>
    {candidates.error && <Alert type="warning" title={candidates.expired
      ? '可选范围已过期，请刷新可选范围。' : candidates.error}/>}
    {directory.error != null && <Alert type="warning" title={directory.error instanceof Error
      ? directory.error.message : '当前空间读取失败，请刷新可选范围。'}/>}
    {directory.warning && <Alert type="warning" title={directory.warning}/>}
    {selectionError && <Alert type="warning" title={selectionError}/>}
    <Typography.Paragraph>租户政策和空间政策分别按当前治理资格管理。修改政策不会自动分享模型或分配预算。</Typography.Paragraph>
    {unsafe && <Alert type="info" title="当前有政策修改或待确认操作，切换配置对象前将提示处理。"/>}
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
                    loading={selecting} showSearch optionFilterProp="label"
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
