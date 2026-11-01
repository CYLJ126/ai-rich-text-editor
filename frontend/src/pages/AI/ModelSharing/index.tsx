import React, {useEffect, useMemo, useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Button, Flex, Typography} from 'antd';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import {registerWorkspaceGuard, useCurrentWorkspace,} from '@/features/workspace/useWorkspace';
import ModelSharingManager from '@/features/ainew/ModelSharingManager';

export default function ModelSharingPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(
    initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '',
  );
  const workspace = useCurrentWorkspace(userId);
  const context = useMemo(
    () => ({
      userId,
      scope: workspace?.scope ?? {tenantId: '', workspaceId: ''},
    }),
    [userId, workspace?.scope],
  );
  const [unsafe, setUnsafe] = useState(false);
  const [picker, setPicker] = useState(false);
  useEffect(
    () =>
      registerWorkspaceGuard(userId, (_scope, proceed) => {
        if (!unsafe) proceed();
      }),
    [userId, unsafe],
  );
  if (!userId) return <Alert type="info" title="请登录后管理模型授权。"/>;
  return (
    <main style={{maxWidth: 1120, padding: 20, margin: '0 auto'}}>
      <Flex justify="space-between" align="center" wrap gap={12}>
        <Typography.Title level={3}>租户模型与授权</Typography.Title>
        <Flex gap={8}>
          <Button
            disabled={unsafe}
            onClick={() => setPicker((value) => !value)}
          >
            选择管理空间
          </Button>
          <Button href="/AI/Models" disabled={unsafe}>
            模型草稿与发布
          </Button>
        </Flex>
      </Flex>
      <Typography.Paragraph>
        {workspace?.name ?? '尚未选择管理空间'}
      </Typography.Paragraph>
      {unsafe && (
        <Alert
          type="info"
          title="请先保存或放弃草稿、确认原操作后再切换空间或离开。"
        />
      )}
      {(picker || !workspace) && (
        <WorkspacePicker userId={userId} presentation="inline"/>
      )}
      {workspace && (
        <ModelSharingManager context={context} onUnsafeChange={setUnsafe}/>
      )}
    </main>
  );
}
