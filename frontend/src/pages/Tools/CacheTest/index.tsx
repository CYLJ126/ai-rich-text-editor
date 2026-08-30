import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Col,
  Input,
  Row,
  Statistic,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { useActivate, useUnactivate } from 'react-activation';
import PageWrapper from '@/components/PageWrapper';

const { Title, Text } = Typography;

interface TimelineItem {
  time: string;
  action: string;
  type: 'system' | 'user'; // 区分系统事件和用户操作
}

const CacheTestContent: React.FC = () => {
  const [activateCount, setActivateCount] = useState(0);
  const [inputValue, setInputValue] = useState('');
  const [timeline, setTimeline] = useState<TimelineItem[]>([]);
  const renderTime = useRef(new Date().toLocaleTimeString());
  const componentId = useRef(Math.random().toString(36).substr(2, 9));
  const [buttonClickCount, setButtonClickCount] = useState(0);

  // 使用 KeepAlive 的生命周期钩子
  useActivate(() => {
    const now = new Date().toLocaleTimeString();
    console.log('CacheTest页面被激活:', now, 'ID:', componentId.current);

    setActivateCount((prev) => {
      const newCount = prev + 1;
      const newEntry: TimelineItem = {
        time: now,
        action:
          newCount === 1
            ? '首次激活页面'
            : `第${newCount}次激活页面 (从缓存恢复)`,
        type: 'system',
      };

      setTimeline((prevTimeline) => [newEntry, ...prevTimeline.slice(0, 19)]);
      return newCount;
    });
  });

  useUnactivate(() => {
    const now = new Date().toLocaleTimeString();
    console.log('CacheTest页面被失活:', now, 'ID:', componentId.current);

    const newEntry: TimelineItem = {
      time: now,
      action: '页面失活 (保存到缓存)',
      type: 'system',
    };

    setTimeline((prev) => [newEntry, ...prev.slice(0, 19)]);
  });

  useEffect(() => {
    console.log(
      'CacheTest页面组件挂载:',
      renderTime.current,
      'ID:',
      componentId.current,
    );

    return () => {
      console.log(
        'CacheTest页面组件卸载:',
        renderTime.current,
        'ID:',
        componentId.current,
      );
    };
  }, []);

  const addUserAction = (action: string) => {
    const newEntry: TimelineItem = {
      time: new Date().toLocaleTimeString(), // 这里显示当前时间是正确的
      action,
      type: 'user',
    };
    setTimeline((prev) => [newEntry, ...prev.slice(0, 19)]);
  };

  const handleButtonClick = () => {
    setButtonClickCount((prev) => prev + 1);
    addUserAction(`用户点击按钮 (第${buttonClickCount + 1}次)`);
  };

  return (
    <PageContainer>
      <Alert
        title={
          <div>
            <Tag color="blue">组件ID: {componentId.current}</Tag>
            <Tag color="green">渲染时间: {renderTime.current}</Tag>
            <Tag color="orange">激活次数: {activateCount}</Tag>
          </div>
        }
        description={
          <div>
            <p>
              <strong>缓存验证指标:</strong>
            </p>
            <ul style={{ marginBottom: 0 }}>
              <li>组件ID 和渲染时间保持不变 = 组件被缓存 ✅</li>
              <li>激活次数递增 = 页面切换被正确监听 ✅</li>
              <li>用户操作状态保持 = 数据状态被缓存 ✅</li>
              <li>操作时间显示实际操作时间 = 正常行为 ✅</li>
            </ul>
          </div>
        }
        type="info"
        style={{ marginBottom: 16 }}
      />

      <Title level={2}>KeepAlive 缓存测试页面</Title>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="页面激活次数"
              value={activateCount}
              suffix="次"
              valueStyle={{ color: activateCount > 1 ? '#3f8600' : '#cf1322' }}
            />
            <Text type="secondary">
              {activateCount > 1 ? '缓存正常工作' : '等待第二次激活'}
            </Text>
          </Card>
        </Col>

        <Col span={6}>
          <Card>
            <Statistic
              title="按钮点击次数"
              value={buttonClickCount}
              suffix="次"
              valueStyle={{ color: '#1890ff' }}
            />
            <Button
              type="primary"
              onClick={handleButtonClick}
              style={{ marginTop: 8 }}
            >
              点击测试 (+1)
            </Button>
          </Card>
        </Col>

        <Col span={6}>
          <Card title="输入框测试">
            <Input
              value={inputValue}
              onChange={(e) => {
                setInputValue(e.target.value);
                addUserAction(`输入内容: "${e.target.value}"`);
              }}
              placeholder="输入内容测试"
            />
            <div style={{ marginTop: 8 }}>
              <Text type="secondary">
                当前值: <Text code>{inputValue || '(空)'}</Text>
              </Text>
            </div>
          </Card>
        </Col>

        <Col span={6}>
          <Card title="缓存状态">
            <div style={{ textAlign: 'center' }}>
              <div
                style={{
                  color: activateCount > 1 ? '#52c41a' : '#ff4d4f',
                  fontSize: '24px',
                  fontWeight: 'bold',
                }}
              >
                {activateCount > 1 ? '✅ 缓存生效' : '⏳ 等待验证'}
              </div>
              <Text type="secondary">
                {activateCount > 1
                  ? '页面状态已被缓存'
                  : '请切换到其他页面再回来'}
              </Text>
            </div>
          </Card>
        </Col>
      </Row>

      <Row>
        <Col span={24}>
          <Card
            title="操作时间线"
            extra={<Text type="secondary">共 {timeline.length} 条记录</Text>}
          >
            <Timeline
              items={timeline.map((item, index) => ({
                children: (
                  <div>
                    <Tag color={item.type === 'system' ? 'blue' : 'green'}>
                      {item.type === 'system' ? '系统' : '用户'}
                    </Tag>
                    <Text strong>{item.time}</Text> - {item.action}
                  </div>
                ),
                color: item.type === 'system' ? 'blue' : 'green',
              }))}
            />
            {timeline.length === 0 && (
              <Text type="secondary">暂无操作记录，请进行一些操作</Text>
            )}
          </Card>
        </Col>
      </Row>

      <Card style={{ marginTop: 16 }} title="测试说明">
        <Row gutter={16}>
          <Col span={12}>
            <Title level={4}>正确的缓存行为：</Title>
            <ul>
              <li>
                <Text strong>组件ID 不变：</Text> 说明组件实例被缓存
              </li>
              <li>
                <Text strong>渲染时间不变：</Text> 说明组件没有重新创建
              </li>
              <li>
                <Text strong>输入内容保持：</Text> 说明状态被缓存
              </li>
              <li>
                <Text strong>点击次数累计：</Text> 说明状态持续更新
              </li>
              <li>
                <Text strong>激活次数递增：</Text> 说明页面切换被正确监听
              </li>
            </ul>
          </Col>
          <Col span={12}>
            <Title level={4}>关于操作时间：</Title>
            <ul>
              <li>
                <Text strong>系统事件时间：</Text> 显示页面激活/失活的实际时间
              </li>
              <li>
                <Text strong>用户操作时间：</Text> 显示用户实际操作的时间
              </li>
            </ul>
          </Col>
        </Row>
      </Card>
    </PageContainer>
  );
};

const Index: React.FC = () => {
  return (
    <PageWrapper>
      <CacheTestContent />
    </PageWrapper>
  );
};

export default Index;
