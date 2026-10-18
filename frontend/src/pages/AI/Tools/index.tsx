import React, {useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Drawer, Flex, Typography} from 'antd';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import WorkspaceQuickSelect from '@/features/workspace/WorkspaceQuickSelect';
import {useCurrentWorkspace} from '@/features/workspace/useWorkspace';
import ToolWorkspace from '@/features/aitool/ToolWorkspace';
import '@/features/aitool/tools.css';

/** 与 ChatNew 共用当前真实工作空间；不自动初始化模型、预算、工具目录或执行工具。 */
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
        <ToolWorkspace
          key={JSON.stringify([userId, workspace.scope])}
          userId={userId}
          workspace={workspace}
        />
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
