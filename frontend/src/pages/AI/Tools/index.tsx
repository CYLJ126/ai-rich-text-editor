import React, {useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Drawer, Flex, Typography} from 'antd';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
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
  const [manage, setManage] = useState(false);
  if (!userId) return <Alert type="info" title="请登录后查看 AI 工具。"/>;
  return (
    <main className="tool-page">
      <Flex
        className="tool-page-header"
        justify="space-between"
        align="center"
        gap={12}
        wrap
      >
        <div>
          <Typography.Title level={3}>AI 工具</Typography.Title>
          <Typography.Text type="secondary">
            查看工具用途，准备并管理当前空间的个人配置。
          </Typography.Text>
        </div>
        <WorkspaceQuickSelect
          userId={userId}
          scope={workspace?.scope}
          name={workspace?.name}
          onManage={() => setManage(true)}
        />
      </Flex>
      {!workspace ? (
        <WorkspacePicker userId={userId} presentation="inline"/>
      ) : (
        <WorkspaceAiPreparation
          key={JSON.stringify([userId, workspace.scope])}
          scope={workspace.scope}
          context={{userId, scope: workspace.scope}}
          legacy={false}
        >
          <ToolWorkspace userId={userId} workspace={workspace}/>
        </WorkspaceAiPreparation>
      )}
      <Drawer
        title="工作空间"
        open={manage}
        onClose={() => setManage(false)}
        destroyOnHidden
      >
        <WorkspacePicker userId={userId} presentation="inline"/>
      </Drawer>
    </main>
  );
}
