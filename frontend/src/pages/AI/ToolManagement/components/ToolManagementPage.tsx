import {PageContainer} from '@ant-design/pro-components';
import {useAccess, useNavigate} from '@umijs/max';
import {Result} from 'antd';
import React from 'react';

const tabs = [
  {key: 'overview', tab: '概览', path: '/AI/ToolManagement'},
  {key: 'providers', tab: '工具提供者', path: '/AI/ToolManagement/Providers'},
  {key: 'catalog', tab: '工具目录', path: '/AI/ToolManagement/Tools'},
  {key: 'bindings', tab: '用户绑定', path: '/AI/ToolManagement/Bindings'},
  {key: 'assistants', tab: '助手工具', path: '/AI/ToolManagement/Assistants'},
  {key: 'calls', tab: '调用控制台', path: '/AI/ToolManagement/Calls'},
  {key: 'tasks', tab: '异步任务', path: '/AI/ToolManagement/Tasks'},
  {key: 'approvals', tab: '审批安全', path: '/AI/ToolManagement/Approvals'},
  {key: 'workflows', tab: '工作流', path: '/AI/ToolManagement/Workflows'},
  {key: 'observability', tab: '轨迹统计', path: '/AI/ToolManagement/Observability'},
];

export interface ToolManagementPageProps {
  activeKey: 'overview' | 'providers' | 'catalog' | 'bindings' | 'assistants' | 'calls' | 'tasks' | 'approvals' | 'workflows' | 'observability';
  title: string;
  subTitle?: string;
  extra?: React.ReactNode;
  children: React.ReactNode;
}

export default function ToolManagementPage({
                                             activeKey,
                                             title,
                                             subTitle,
                                             extra,
                                             children,
                                           }: ToolManagementPageProps) {
  const access = useAccess();
  const navigate = useNavigate();
  if (!access.canViewAiTools) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="你没有访问 AI 工具中心的权限"
      />
    );
  }
  return (
    <PageContainer
      title={title}
      subTitle={subTitle}
      extra={extra}
      tabList={tabs}
      tabActiveKey={activeKey}
      onTabChange={(key) => {
        const target = tabs.find((item) => item.key === key);
        if (target) navigate(target.path);
      }}
    >
      {children}
    </PageContainer>
  );
}
