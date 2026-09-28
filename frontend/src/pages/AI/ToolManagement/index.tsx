import {
  ApiOutlined,
  AuditOutlined,
  BranchesOutlined,
  LinkOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import {useNavigate} from '@umijs/max';
import {Alert, Card, Col, Row, Space, Tag, Typography} from 'antd';
import React from 'react';
import {ToolManagementPage} from './components';

const foundations = [
  {
    title: '工具目录',
    description: '提供者、工具定义与不可变版本的统一管理入口。',
    icon: <ToolOutlined/>,
    status: '基础能力已就绪',
    path: '/AI/ToolManagement/Tools',
  },
  {
    title: '用户工具绑定',
    description: '固定工具版本、配置、凭据引用与收紧策略。',
    icon: <LinkOutlined/>,
    status: '管理能力已就绪',
    path: '/AI/ToolManagement/Bindings',
  },
  {
    title: '助手工具配置',
    description: '管理每个助手可见的工具集合、顺序和助手级策略。',
    icon: <RobotOutlined/>,
    status: '管理能力已就绪',
    path: '/AI/ToolManagement/Assistants',
  },
  {
    title: '统一调用',
    description: '同步、非阻塞和后台任务共享同一套调用契约。',
    icon: <ApiOutlined/>,
    status: '调用闭环已就绪',
    path: '/AI/ToolManagement/Calls',
  },
  {
    title: '异步任务',
    description: '查询持久化任务、执行结果，并支持取消和安全恢复。',
    icon: <AuditOutlined/>,
    status: '任务闭环已就绪',
    path: '/AI/ToolManagement/Tasks',
  },
  {
    title: '安全审批',
    description: '权限、Guardrail 与高风险人工审批统一呈现。',
    icon: <SafetyCertificateOutlined/>,
    status: '审批安全闭环已就绪',
    path: '/AI/ToolManagement/Approvals',
  },
  {
    title: '工作流',
    description: '为后续可视化编排复用 Schema 表单与工具版本引用。',
    icon: <BranchesOutlined/>,
    status: '公共组件已就绪',
  },
  {
    title: '可观测性',
    description: '查询调用明细、基础统计和完整执行事件轨迹。',
    icon: <AuditOutlined/>,
    status: '轨迹统计闭环已就绪',
    path: '/AI/ToolManagement/Observability',
  },
];

export default function ToolManagement() {
  const navigate = useNavigate();

  return (
    <ToolManagementPage
      activeKey="overview"
      title="AI 工具中心"
      subTitle="创建、配置、调用和编排可审计的 AI 工具"
    >
      <Alert
        showIcon
        type="info"
        message="一期前端公共基础已启用"
        description="当前页面作为工具模块入口。工具目录、绑定、助手配置、调用、异步任务、审批安全和轨迹统计已接入；工作流将在后续功能闭环中接入。"
        style={{marginBottom: 16}}
      />
      <Row gutter={[16, 16]}>
        {foundations.map((item) => (
          <Col key={item.title} xs={24} md={12} xl={8}>
            <Card
              hoverable={Boolean(item.path)}
              style={{height: '100%'}}
              onClick={() => item.path && navigate(item.path)}
            >
              <Space align="start" size={12}>
                <Typography.Title level={3} style={{margin: 0}}>
                  {item.icon}
                </Typography.Title>
                <Space direction="vertical" size={4}>
                  <Typography.Title level={5} style={{margin: 0}}>
                    {item.title}
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    {item.description}
                  </Typography.Text>
                  <Tag color="success">{item.status}</Tag>
                </Space>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>
    </ToolManagementPage>
  );
}
