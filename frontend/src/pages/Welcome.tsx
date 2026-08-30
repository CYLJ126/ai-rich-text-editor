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
        message={`缓存状态检测 - 激活次数: ${activateCount}, 最后激活: ${lastActivateTime}`}
        type="info"
        style={{ marginBottom: 16 }}
      />

      <Card title="Welcome 页面缓存测试">
        <div style={{ marginBottom: 16 }}>
          <p><strong>组件渲染时间:</strong> {renderTime.current}</p>
          <p><strong>激活次数:</strong> {activateCount}</p>
          <p><strong>最后激活时间:</strong> {lastActivateTime}</p>
          <p style={{ color: activateCount > 1 ? 'green' : 'red' }}>
            {activateCount > 1 ? '✅ 缓存正常工作' : '❌ 缓存可能未生效'}
          </p>
        </div>

        <div style={{ marginBottom: 16 }}>
          <p><strong>计数器:</strong> {counter}</p>
          <Button onClick={() => setCounter(c => c + 1)} type="primary">
            点击计数 +1
          </Button>
        </div>

        <div style={{ marginBottom: 16 }}>
          <p><strong>输入框测试:</strong></p>
          <Input
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder="输入一些内容测试缓存"
          />
          <p>输入的内容: {inputValue}</p>
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
