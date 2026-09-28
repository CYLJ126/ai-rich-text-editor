import {Alert, Card, Col, Empty, Progress, Row, Statistic, Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import React from 'react';
import {formatDuration, formatSuccessRate} from '@/features/ai-tool';
import type {ToolCallStatistics} from '@/types/ai.tool.type';

export interface CallStatisticsOverviewProps {
  statistics?: ToolCallStatistics;
  loading: boolean;
  toolId?: string;
}

interface FailureReasonRow {
  code: string;
  count: number;
}

export default function CallStatisticsOverview({
                                                 statistics,
                                                 loading,
                                                 toolId,
                                               }: CallStatisticsOverviewProps) {
  const failureReasons = Object.entries(statistics?.failureReasons || {})
    .map(([code, count]) => ({code, count}))
    .sort((left, right) => right.count - left.count);
  const terminalTotal = statistics
    ? statistics.succeeded + statistics.failed + statistics.denied
    : 0;
  const otherTotal = Math.max(0, (statistics?.total || 0) - terminalTotal);

  const columns: ColumnsType<FailureReasonRow> = [
    {
      title: '错误码',
      dataIndex: 'code',
      render: (value: string) => <code>{value}</code>,
    },
    {title: '次数', dataIndex: 'count', width: 90},
    {
      title: '占失败调用比例',
      key: 'rate',
      width: 180,
      render: (_, record) => {
        const failed = (statistics?.failed || 0) + (statistics?.denied || 0);
        return failed ? formatSuccessRate(record.count / failed) : '0.0%';
      },
    },
  ];

  return (
    <div>
      <Alert
        type="info"
        showIcon
        message={toolId ? `当前筛选工具：${toolId}` : '全部工具实时统计'}
        description="一期统计从当前用户最近最多 500 条调用记录实时计算；按日趋势和长期聚合将在二期聚合表中提供。"
        style={{marginBottom: 16}}
      />
      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <Card loading={loading} size="small">
            <Statistic title="样本调用量" value={statistics?.total || 0}/>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={loading} size="small">
            <Statistic
              title="成功率"
              value={formatSuccessRate(statistics?.successRate || 0)}
              valueStyle={{color: '#52c41a'}}
            />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={loading} size="small">
            <Statistic
              title="平均耗时"
              value={formatDuration(statistics?.averageLatencyMs)}
            />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={loading} size="small">
            <Statistic title="重试次数" value={statistics?.retries || 0}/>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="状态分布" loading={loading} style={{height: '100%'}}>
            {statistics?.total ? (
              <Row gutter={[16, 16]}>
                <Col span={6}>
                  <Statistic
                    title="成功"
                    value={statistics?.succeeded || 0}
                    valueStyle={{color: '#52c41a'}}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title="失败"
                    value={statistics?.failed || 0}
                    valueStyle={{color: '#ff4d4f'}}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title="安全拒绝"
                    value={statistics?.denied || 0}
                    valueStyle={{color: '#fa8c16'}}
                  />
                </Col>
                <Col span={6}>
                  <Statistic title="处理中/其他" value={otherTotal}/>
                </Col>
                <Col span={24}>
                  <Progress
                    percent={Number(
                      ((statistics?.successRate || 0) * 100).toFixed(1),
                    )}
                    strokeColor="#52c41a"
                  />
                </Col>
              </Row>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="Token 用量" loading={loading} style={{height: '100%'}}>
            <Row gutter={16}>
              <Col span={8}>
                <Statistic title="输入" value={statistics?.inputTokens || 0}/>
              </Col>
              <Col span={8}>
                <Statistic title="输出" value={statistics?.outputTokens || 0}/>
              </Col>
              <Col span={8}>
                <Statistic
                  title="合计"
                  value={
                    (statistics?.inputTokens || 0) +
                    (statistics?.outputTokens || 0)
                  }
                />
              </Col>
            </Row>
          </Card>
        </Col>
        <Col span={24}>
          <Card title="失败原因" loading={loading}>
            {failureReasons.length ? (
              <Table<FailureReasonRow>
                rowKey="code"
                columns={columns}
                dataSource={failureReasons}
                pagination={false}
                size="small"
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="当前样本没有失败原因"
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
