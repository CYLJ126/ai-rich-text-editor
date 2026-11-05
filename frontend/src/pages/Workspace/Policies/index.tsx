import React, {useEffect} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Typography} from 'antd';
import {useAiRoute} from '@/features/ainew/aiNavigation';
import {
  readConfigurationLocation,
  workspaceConfigurationLocation
} from '@/features/configuration/configurationLocation';
import {TenantSettings} from '@/pages/Environment/Tenants';

export default function PolicySettingsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const search = useAiRoute();
  if (!userId) return <Alert type="info" title="请登录后管理租户和空间政策。"/>;
  try {
    const {tenantId, workspaceId, tab} = readConfigurationLocation(search, userId);
    if (tab !== undefined && !['tenant', 'workspace', 'policy'].includes(tab)) throw new Error('政策页签无效');
    if (tab === 'workspace' && !workspaceId)
      return <Alert type="info" title="请选择要配置政策的工作空间。"
                    action={<Button onClick={() => history.push('/Environment/Workspaces')}>选择工作空间</Button>}/>;
    if (tenantId && workspaceId && tab !== 'tenant')
      return <SpacePolicyLocation to={workspaceConfigurationLocation(userId, {tenantId, workspaceId}, 'policy')}/>;
    return <TenantSettings key={JSON.stringify([userId, tenantId])} userId={userId}
                           initialTenantId={tenantId} initialTab="policy"/>;
  } catch {
    return <Alert type="warning" title="政策定位无效，请重新选择配置对象。"
                  action={<Button onClick={() => history.replace('/Environment/Tenants')}>打开租户目录</Button>}/>;
  }
}

function SpacePolicyLocation({to}: { to: string }) {
  useEffect(() => {
    history.replace(to);
  }, [to]);
  return <Typography.Paragraph role="status">正在打开工作空间政策…</Typography.Paragraph>;
}
