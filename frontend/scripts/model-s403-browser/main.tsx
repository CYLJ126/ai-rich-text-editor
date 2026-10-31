import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {Alert, Button, ConfigProvider, Flex, Typography} from 'antd';
import 'antd/dist/reset.css';
import PersonalModelManager from '../../src/features/ainew/PersonalModelManager';
import type {ChatModelOption} from '../../src/services/arte-ai/types';

const context = await fetch('/fixture/info').then(value => value.json());

function Acceptance() {
  const [narrow, setNarrow] = useState(false);
  const [options, setOptions] = useState<ChatModelOption[]>([]);
  const [result, setResult] = useState('');
  return <ConfigProvider theme={{cssVar: {key: 'model-management-acceptance'}}}>
    <main style={{maxWidth: narrow ? 390 : 1160, padding: 16, margin: 'auto', boxSizing: 'border-box'}}>
      <Typography.Title level={3}>个人模型管理 · 4-03</Typography.Title>
      <Alert type="info"
             title="本机隔离验收：真实 MVC／公共授权／H2／SM4／回环供应商。固定测试会话，不验证生产 JWT，不访问业务库。"/>
      <Flex gap={8} wrap style={{margin: '12px 0'}}>
        <Button onClick={() => setNarrow(value => !value)}>{narrow ? '宽屏预览' : '390px 容器预览'}</Button>
        <Button
          onClick={() => void fetch('/fixture/discover').then(value => value.json()).then(value => setOptions(value.options))}>查看当前可用目录</Button>
      </Flex>
      {options.map(option => <Flex key={option.binding.id} gap={8} wrap style={{margin: '8px 0'}}>
        <Typography.Text>{option.displayName} · {option.source ?? '原目录'} · {option.budgetRefs.length} 个兼容预算</Typography.Text>
        {option.source === 'PERSONAL' &&
          <Button onClick={() => void fetch('/fixture/run', {method: 'POST', body: JSON.stringify(option.binding)})
            .then(value => value.json()).then(value => setResult(`${value.state} · ${value.settlement.includes('SETTLED') ? 'SETTLED' : value.settlement}`))}>执行一次隔离聊天</Button>}
      </Flex>)}
      {result && <Alert type="success" title={`真实执行与结算：${result}`}/>}
      <PersonalModelManager context={context} onUpdated={() => setOptions([])}/>
    </main>
  </ConfigProvider>;
}

createRoot(document.getElementById('root')!).render(<Acceptance/>);
