import React, {useCallback, useEffect, useRef, useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Button, Flex, Tabs, Typography} from 'antd';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import PolicyEditor from '@/features/workspace/PolicyEditor';
import {protectWorkspaceChange, registerWorkspaceGuard, useWorkspaceDirectory} from '@/features/workspace/useWorkspace';
import type {PolicySnapshot, TenantPolicyView} from '@/services/platform/policy';
import './style.css';

export default function PolicySettingsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理租户和空间政策。"/>;
  return <PolicySettings key={userId} userId={userId}/>;
}

export function PolicySettings({userId}: { userId: string }) {
  const directory = useWorkspaceDirectory(userId);
  const current = directory.current;
  const [showSpaces, setShowSpaces] = useState(false);
  const [tab, setTab] = useState('tenant');
  const [tenantUnsafe, setTenantUnsafe] = useState(false);
  const [workspaceUnsafe, setWorkspaceUnsafe] = useState(false);
  const [guardMessage, setGuardMessage] = useState('');
  const [spaceRevision, setSpaceRevision] = useState(0);
  const [tenantView, setTenantView] = useState<TenantPolicyView | null>(null);
  const unsafe = tenantUnsafe || workspaceUnsafe;
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
  }, [current?.scope.tenantId, current?.scope.workspaceId]);
  const loaded = useCallback((snapshot: PolicySnapshot | null) => {
    setTenantView(snapshot?.kind === 'tenant' ? snapshot.value : null);
  }, []);
  const tenantSaved = useCallback(() => setSpaceRevision(value => value + 1), []);

  function personal() {
    const target = directory.preference?.defaultScope;
    if (!target) return;
    if (current?.scope.tenantId === target.tenantId) {
      setTab('tenant');
      return;
    }
    protectWorkspaceChange(userId, target, () => {
      const selection = ++attempt.current;
      void directory.select(target).then(opened => {
        if (opened && attempt.current === selection) setTab('tenant');
      });
    });
  }

  return <main className="workspace-policy-page" aria-label="租户与空间政策设置">
    <Flex component="header" justify="space-between" align="center" wrap gap={12}>
      <div><Typography.Title level={3}>租户与空间政策</Typography.Title>
        <Typography.Text>{current?.name ?? '尚未选择工作空间'}</Typography.Text></div>
      <Flex gap={8} wrap>
        <Button disabled={unsafe || directory.selecting}
                onClick={() => setShowSpaces(value => !value)}>选择工作空间</Button>
        <Button
          disabled={unsafe || !directory.preference || !!directory.pending || directory.recoveryBlocked || directory.selecting}
          onClick={personal}>我的个人租户政策</Button>
        <Button href="/AI/Models" disabled={unsafe}>返回模型管理</Button>
      </Flex>
    </Flex>
    <Typography.Paragraph>租户政策和空间政策分别按当前治理资格管理。个人租户也有独立政策，修改政策不会自动分享模型或分配预算。</Typography.Paragraph>
    {(unsafe || guardMessage) &&
      <Alert type="info" title={guardMessage || '当前有政策草稿或待确认操作，请先处理后再离开或切换空间。'}/>}
    {(showSpaces || !current) && <WorkspacePicker userId={userId} presentation="inline"/>}
    {current &&
      <Tabs key={JSON.stringify([userId, current.scope])} activeKey={tab} onChange={setTab} destroyOnHidden={false}
            items={[
              {
                key: 'tenant', label: '租户政策（含个人）', forceRender: true, children: <PolicyEditor userId={userId}
                                                                                                     scope={current.scope}
                                                                                                     kind="tenant"
                                                                                                     onUnsafeChange={setTenantUnsafe}
                                                                                                     onSaved={tenantSaved}
                                                                                                     onSnapshot={loaded}/>
              },
              {
                key: 'workspace',
                label: '空间政策',
                forceRender: true,
                children: <PolicyEditor userId={userId} scope={current.scope}
                                        workspaceName={current.name} kind="workspace" ceiling={tenantView}
                                        refreshRevision={spaceRevision} onUnsafeChange={setWorkspaceUnsafe}/>
              },
            ]}/>}
  </main>;
}
