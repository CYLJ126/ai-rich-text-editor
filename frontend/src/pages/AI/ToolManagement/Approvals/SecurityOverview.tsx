import {LockOutlined, SafetyCertificateOutlined} from '@ant-design/icons';
import {Alert, Card, Empty, Space, Table, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import React from 'react';
import {ToolErrorAlert} from '@/components/AITool';
import {GUARDRAIL_DESCRIPTIONS} from '@/features/ai-tool';
import type {GuardrailPhase, ToolGuardrailView} from '@/types/ai.tool.type';

export interface SecurityOverviewProps {
  guardrails: ToolGuardrailView[];
  loading: boolean;
  error?: unknown;
}

const phaseLabels: Record<GuardrailPhase, string> = {
  discovery: '发现阶段',
  input: '输入阶段',
  'pre-execution': '执行前',
  'post-execution': '执行后',
  output: '输出阶段',
};

export default function SecurityOverview({
                                           guardrails,
                                           loading,
                                           error,
                                         }: SecurityOverviewProps) {
  const columns: ColumnsType<ToolGuardrailView> = [
    {title: '顺序', dataIndex: 'order', width: 72},
    {
      title: 'Guardrail',
      dataIndex: 'name',
      render: (name: string, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{name}</Typography.Text>
          <Typography.Text type="secondary">
            {GUARDRAIL_DESCRIPTIONS[name] || record.implementation}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '执行阶段',
      dataIndex: 'phases',
      width: 240,
      render: (phases: GuardrailPhase[]) => (
        <Space wrap>
          {phases.map((phase) => (
            <Tag key={phase} color="blue">
              {phaseLabels[phase] || phase}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '策略来源',
      dataIndex: 'serverEnforced',
      width: 130,
      render: (enforced: boolean) => (
        <Tag color={enforced ? 'success' : 'default'}>
          {enforced ? '服务端强制' : '可配置'}
        </Tag>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{width: '100%'}}>
      <Alert
        type="success"
        showIcon
        icon={<SafetyCertificateOutlined/>}
        message="安全规则在服务端统一执行"
        description="REST、Agent、Workflow 和 MCP 调用都经过同一执行管道。用户配置只能收紧策略，不能关闭服务端授权、Schema 校验或固定 Guardrail。"
      />
      <Card
        title={
          <Space>
            <LockOutlined/>
            固定 Guardrail 执行链
          </Space>
        }
      >
        {error ? (
          <ToolErrorAlert error={error} showDetails/>
        ) : guardrails.length || loading ? (
          <Table<ToolGuardrailView>
            rowKey="name"
            loading={loading}
            columns={columns}
            dataSource={guardrails}
            pagination={false}
            size="small"
          />
        ) : (
          <Empty description="当前没有已加载的 Guardrail"/>
        )}
      </Card>
      <Card size="small" title="安全边界">
        <Space wrap size={[8, 12]}>
          <Tag color="blue">身份取自登录上下文</Tag>
          <Tag color="blue">固定工具版本与绑定检查</Tag>
          <Tag color="blue">输入长度和未知字段校验</Tag>
          <Tag color="blue">凭据只传引用</Tag>
          <Tag color="blue">高风险强制审批</Tag>
          <Tag color="blue">输出 Schema 校验与脱敏</Tag>
          <Tag color="blue">调用事件全程留痕</Tag>
        </Space>
      </Card>
    </Space>
  );
}
