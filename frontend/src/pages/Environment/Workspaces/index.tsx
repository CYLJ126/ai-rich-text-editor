import React from 'react';
import {useModel} from '@umijs/max';
import {Alert, Typography} from 'antd';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';

export default function WorkspaceConfigurationPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理工作空间。"/>;
  return <main style={{maxWidth: 1120, margin: '0 auto', padding: 20}} aria-label="工作空间配置">
    <Typography.Title level={3}>工作空间配置</Typography.Title>
    <Typography.Paragraph>管理当前账号可访问的工作空间，维护名称、描述和个人偏好。</Typography.Paragraph>
    <WorkspacePicker key={userId} userId={userId} presentation="inline"/>
  </main>;
}
