import React, {useEffect, useMemo, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Typography} from 'antd';
import PersonalModelManager from '@/features/ainew/PersonalModelManager';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import {registerWorkspaceGuard, useCurrentWorkspace} from '@/features/workspace/useWorkspace';
import '@/features/ainew/personalModels.css';

/** 普通登录用户的空间模型管理入口；不依赖空间 AI 或预算已准备，不以管理员角色开放操作。 */
export default function ModelsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const workspace = useCurrentWorkspace(userId);
  const context = useMemo(() => ({
    userId,
    scope: workspace?.scope ?? {tenantId: '', workspaceId: ''}
  }), [userId, workspace?.scope]);
  const [unsafe, setUnsafe] = useState(false);
  const [showSpaces, setShowSpaces] = useState(false);
  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (!unsafe) proceed();
  }), [userId, unsafe]);
  if (!userId) return <Alert type="info" title="请登录后管理模型。"/>;
  return <main className="personal-models-page" aria-label="空间模型管理页面">
    <Flex component="header" justify="space-between" align="center" wrap gap={12}>
      <div><Typography.Title
        level={3}>模型管理</Typography.Title><Typography.Text>{workspace?.name ?? '尚未选择工作空间'}</Typography.Text>
      </div>
      <Flex gap={8} wrap><Button disabled={unsafe}
                                 onClick={() => setShowSpaces(value => !value)}>选择工作空间</Button><Button
        onClick={() => history.push('/AI/ChatNew')} disabled={unsafe}>返回聊天</Button></Flex>
    </Flex>
    {unsafe && <Alert type="info" title="请先保存或放弃未保存内容、清除临时密钥，或确认恢复记录后再切换。"/>}
    {(showSpaces || !workspace) && <WorkspacePicker userId={userId} presentation="inline"/>}
    {workspace && <PersonalModelManager context={context} onUnsafeChange={setUnsafe} onUpdated={() => {
    }}/>}
  </main>;
}
