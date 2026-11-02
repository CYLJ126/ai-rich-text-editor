import React from 'react';
import {Link, useModel} from '@umijs/max';
import {Alert, Card, Col, Row, Typography} from 'antd';
import {environmentEntries} from '@/features/environment/navigation';

export default function EnvironmentPage() {
  const {initialState} = useModel('@@initialState');
  const user = initialState?.currentUser;
  if (!user?.id && !user?.userid) return <Alert type="info" title="请登录后查看环境配置。"/>;
  const entries = environmentEntries.filter(entry => user.menus?.includes(entry.menuCode));
  return <main style={{maxWidth: 1120, margin: '0 auto', padding: 20}} aria-label="环境配置">
    <Typography.Title level={3}>环境配置</Typography.Title>
    <Typography.Paragraph>选择需要维护的配置，具体操作以当前账号的租户、空间及资源权限为准。</Typography.Paragraph>
    {!entries.length && <Alert type="info" title="暂无已分配的配置入口，请联系管理员分配菜单。"/>}
    <Row gutter={[16, 16]}>
      {entries.map(entry => <Col xs={24} sm={12} key={entry.menuCode}>
        <Card title={entry.title}>
          <Typography.Paragraph>{entry.description}</Typography.Paragraph>
          <Link to={entry.path}>进入{entry.title}</Link>
        </Card>
      </Col>)}
    </Row>
  </main>;
}
