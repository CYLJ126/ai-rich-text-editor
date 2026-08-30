import {i18nText} from '@/utils/i18n';
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
            ? i18nText("app.tools.cachetest.ab9a2bd5")
            : i18nText("app.tools.cachetest.52132a65", {value0: newCount}),
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
      action: i18nText("app.tools.cachetest.6586eff9"),
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
    addUserAction(i18nText("app.tools.cachetest.a89e0480", {value0: buttonClickCount + 1}));
  };

  return (
    <PageContainer>
      <Alert
        title={
          <div>
            <Tag color="blue">{i18nText("app.tools.cachetest.47389856")} {componentId.current}</Tag>
            <Tag color="green">{i18nText("app.tools.cachetest.150da999")} {renderTime.current}</Tag>
            <Tag color="orange">{i18nText("app.tools.cachetest.2234b323")} {activateCount}</Tag>
          </div>
        }
        description={
          <div>
            <p>
              <strong>{i18nText("app.tools.cachetest.5a7a9581")}</strong>
            </p>
            <ul style={{ marginBottom: 0 }}>
              <li>{i18nText("app.tools.cachetest.d56451c4")}</li>
              <li>{i18nText("app.tools.cachetest.f1c36ea2")}</li>
              <li>{i18nText("app.tools.cachetest.5ba894aa")}</li>
              <li>{i18nText("app.tools.cachetest.959b0f17")}</li>
            </ul>
          </div>
        }
        type="info"
        style={{ marginBottom: 16 }}
      />

      <Title level={2}>{i18nText("app.tools.cachetest.04abe282")}</Title>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title={i18nText("app.tools.cachetest.e2965c40")}
              value={activateCount}
              suffix={i18nText("app.tools.cachetest.c1ff57ac")}
              valueStyle={{ color: activateCount > 1 ? '#3f8600' : '#cf1322' }}
            />
            <Text type="secondary">
              {activateCount > 1 ? i18nText("app.tools.cachetest.460c29fb") : i18nText("app.tools.cachetest.aa10c669")}
            </Text>
          </Card>
        </Col>

        <Col span={6}>
          <Card>
            <Statistic
              title={i18nText("app.tools.cachetest.723a4b56")}
              value={buttonClickCount}
              suffix={i18nText("app.tools.cachetest.c1ff57ac")}
              valueStyle={{ color: '#1890ff' }}
            />
            <Button
              type="primary"
              onClick={handleButtonClick}
              style={{ marginTop: 8 }}
            >
              {i18nText("app.tools.cachetest.6ecd2c1d")}
            </Button>
          </Card>
        </Col>

        <Col span={6}>
          <Card title={i18nText("app.tools.cachetest.973e79c8")}>
            <Input
              value={inputValue}
              onChange={(e) => {
                setInputValue(e.target.value);
                addUserAction(i18nText("app.tools.cachetest.e8eb43b6", {value0: e.target.value}));
              }}
              placeholder={i18nText("app.tools.cachetest.c572f1d3")}
            />
            <div style={{ marginTop: 8 }}>
              <Text type="secondary">
                {i18nText("app.tools.cachetest.d546f82c")} <Text code>{inputValue || i18nText("app.tools.cachetest.36a199f4")}</Text>
              </Text>
            </div>
          </Card>
        </Col>

        <Col span={6}>
          <Card title={i18nText("app.tools.cachetest.6878dd80")}>
            <div style={{ textAlign: 'center' }}>
              <div
                style={{
                  color: activateCount > 1 ? '#52c41a' : '#ff4d4f',
                  fontSize: '24px',
                  fontWeight: 'bold',
                }}
              >
                {activateCount > 1 ? i18nText("app.tools.cachetest.7816168b") : i18nText("app.tools.cachetest.f60d444f")}
              </div>
              <Text type="secondary">
                {activateCount > 1
                  ? i18nText("app.tools.cachetest.0e0a8bc4")
                  : i18nText("app.tools.cachetest.c75a6fce")}
              </Text>
            </div>
          </Card>
        </Col>
      </Row>

      <Row>
        <Col span={24}>
          <Card
            title={i18nText("app.tools.cachetest.b4f0f526")}
            extra={<Text type="secondary">{i18nText("app.tools.cachetest.673638f1")} {timeline.length} {i18nText("app.tools.cachetest.8eb39d85")}</Text>}
          >
            <Timeline
              items={timeline.map((item, index) => ({
                children: (
                  <div>
                    <Tag color={item.type === 'system' ? 'blue' : 'green'}>
                      {item.type === 'system' ? i18nText("app.tools.cachetest.9adc823d") : i18nText("app.tools.cachetest.1452c9ee")}
                    </Tag>
                    <Text strong>{item.time}</Text> - {item.action}
                  </div>
                ),
                color: item.type === 'system' ? 'blue' : 'green',
              }))}
            />
            {timeline.length === 0 && (
              <Text type="secondary">{i18nText("app.tools.cachetest.e2cb1c50")}</Text>
            )}
          </Card>
        </Col>
      </Row>

      <Card style={{ marginTop: 16 }} title={i18nText("app.tools.cachetest.cacdc10e")}>
        <Row gutter={16}>
          <Col span={12}>
            <Title level={4}>{i18nText("app.tools.cachetest.f6407ed0")}</Title>
            <ul>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.b94da554")}</Text> {i18nText("app.tools.cachetest.a5287c16")}
              </li>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.3dbacb3a")}</Text> {i18nText("app.tools.cachetest.739c67ea")}
              </li>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.2075c253")}</Text> {i18nText("app.tools.cachetest.0e5dcd9f")}
              </li>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.a1a768f2")}</Text> {i18nText("app.tools.cachetest.124d1e02")}
              </li>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.7883af87")}</Text> {i18nText("app.tools.cachetest.09a4d497")}
              </li>
            </ul>
          </Col>
          <Col span={12}>
            <Title level={4}>{i18nText("app.tools.cachetest.eeadfb3a")}</Title>
            <ul>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.64e5d82e")}</Text> {i18nText("app.tools.cachetest.2f1fa651")}
              </li>
              <li>
                <Text strong>{i18nText("app.tools.cachetest.f19ebcc7")}</Text> {i18nText("app.tools.cachetest.ec212740")}
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
