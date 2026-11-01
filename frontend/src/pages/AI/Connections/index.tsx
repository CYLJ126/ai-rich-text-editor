import React, {useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Button, Flex, Typography} from 'antd';
import ConnectionManager from '@/features/ainew/ConnectionManager';

export default function ConnectionsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const [unsafe, setUnsafe] = useState(false);
  if (!userId) return <Alert type="info" title="请登录后管理审核连接。"/>;
  return <main style={{maxWidth: 960, margin: '0 auto', padding: 24}}>
    <Flex justify="space-between" align="center" wrap gap={12}>
      <Typography.Title level={3}>平台审核连接管理</Typography.Title>
      <Button href="/AI/Models" disabled={unsafe}>返回模型管理</Button>
    </Flex>
    <ConnectionManager key={userId} userId={userId} onUnsafeChange={setUnsafe}/>
  </main>;
}
