import {i18nText} from '@/utils/i18n';
// src/pages/Welcome.tsx
import React, { useState, useEffect, useRef } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Input, Alert } from 'antd';
import { useActivate, useUnactivate } from 'react-activation';
import PageWrapper from '@/components/PageWrapper';

const WelcomeContent: React.FC = () => {
  const [counter, setCounter] = useState(0);
  const [inputValue, setInputValue] = useState('');
  const renderTime = useRef(new Date().toLocaleTimeString());
  const [activateCount, setActivateCount] = useState(0);
  const [lastActivateTime, setLastActivateTime] = useState<string>('');

  // KeepAlive 特有的生命周期钩子
  useActivate(() => {
    const now = new Date().toLocaleTimeString();
    console.log('Welcome页面被激活:', now);
    setActivateCount(prev => prev + 1);
    setLastActivateTime(now);
  });

  useUnactivate(() => {
    console.log('Welcome页面被失活:', new Date().toLocaleTimeString());
  });

  useEffect(() => {
    console.log('Welcome页面首次挂载:', renderTime.current);

    return () => {
      console.log('Welcome页面卸载:', renderTime.current);
    };
  }, []);

  return (
    <PageContainer>
      <Alert
        message={i18nText("app.common.pages.welcome.29a05a85", {value0: activateCount, value1: lastActivateTime})}
        type="info"
        style={{ marginBottom: 16 }}
      />

      <Card title={i18nText("app.common.pages.welcome.c8a02b06")}>
        <div style={{ marginBottom: 16 }}>
          <p><strong>{i18nText("app.common.pages.welcome.eaf9e2e5")}</strong> {renderTime.current}</p>
          <p><strong>{i18nText("app.common.pages.welcome.cd31da57")}</strong> {activateCount}</p>
          <p><strong>{i18nText("app.common.pages.welcome.ea022c68")}</strong> {lastActivateTime}</p>
          <p style={{ color: activateCount > 1 ? 'green' : 'red' }}>
            {activateCount > 1 ? i18nText("app.common.pages.welcome.5badb394") : i18nText("app.common.pages.welcome.cd2de43d")}
          </p>
        </div>

        <div style={{ marginBottom: 16 }}>
          <p><strong>{i18nText("app.common.pages.welcome.97116082")}</strong> {counter}</p>
          <Button onClick={() => setCounter(c => c + 1)} type="primary">
            {i18nText("app.common.pages.welcome.2903160a")}
          </Button>
        </div>

        <div style={{ marginBottom: 16 }}>
          <p><strong>{i18nText("app.common.pages.welcome.08263c8c")}</strong></p>
          <Input
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder={i18nText("app.common.pages.welcome.c2199239")}
          />
          <p>{i18nText("app.common.pages.welcome.538f17c3")} {inputValue}</p>
        </div>
      </Card>
    </PageContainer>
  );
};

const Welcome: React.FC = () => {
  console.log('Welcome组件重新渲染:', new Date().toLocaleTimeString());

  return (
    <PageWrapper>
      <WelcomeContent />
    </PageWrapper>
  );
};

export default Welcome;
