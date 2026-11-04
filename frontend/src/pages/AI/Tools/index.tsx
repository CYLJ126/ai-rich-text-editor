import React from 'react';
import {useModel} from '@umijs/max';
import {Alert, Typography} from 'antd';
import WorkspaceQuickSelect from '@/features/workspace/WorkspaceQuickSelect';
import {useCurrentWorkspace} from '@/features/workspace/useWorkspace';
import ToolWorkspace from '@/features/aitool/ToolWorkspace';
import WorkspaceAiPreparation from '@/features/ainew/WorkspaceAiPreparation';
import '@/features/aitool/tools.css';

/** 与 ChatNew 共用真实工作空间及按需 AI 准备入口；不自动发布工具或执行调用。 */
export default function ToolsPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(
    initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '',
  );
  const workspace = useCurrentWorkspace(userId);
  if (!userId) return <Alert type="info" title="请登录后查看 AI 工具。"/>;
  const workspaceSelect = <WorkspaceQuickSelect userId={userId} scope={workspace?.scope} name={workspace?.name}/>;
  const workspaceFallback = <div className="tool-workspace-select">{workspaceSelect}</div>;
  return (
    <main className="tool-page">
      {!workspace ? (
        <>{workspaceFallback}<Typography.Paragraph>请从上方选择工作空间。</Typography.Paragraph></>
      ) : (
        <WorkspaceAiPreparation
          key={JSON.stringify([userId, workspace.scope])}
          scope={workspace.scope}
          context={{userId, scope: workspace.scope}}
          legacy={false}
          fallback={workspaceFallback}
        >
          <ToolWorkspace userId={userId} workspace={workspace} tabBarExtraContent={workspaceSelect}/>
        </WorkspaceAiPreparation>
      )}
    </main>
  );
}
