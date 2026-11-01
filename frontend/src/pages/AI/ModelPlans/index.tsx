import React, {useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Button, Flex, Typography} from 'antd';
import ModelPlanManager from '@/features/ainew/ModelPlanManager';

export default function ModelPlansPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const [unsafe, setUnsafe] = useState(false);
  if (!userId) return <Alert type="info" title="请登录后管理审核模型与费率。"/>;
  return <main style={{maxWidth: 960, margin: '0 auto', padding: 24}}>
    <Flex justify="space-between" align="center" wrap gap={12}><Typography.Title
      level={3}>审核模型与费率</Typography.Title>
      <Flex gap={8} wrap><Button href="/AI/Connections" disabled={unsafe}>管理审核连接</Button>
        <Button href="/Workspace/Policies" disabled={unsafe}>租户与空间政策</Button><Button href="/AI/Models"
                                                                                            disabled={unsafe}>返回模型管理</Button></Flex>
    </Flex><ModelPlanManager key={userId} userId={userId} onUnsafeChange={setUnsafe}/>
  </main>;
}
