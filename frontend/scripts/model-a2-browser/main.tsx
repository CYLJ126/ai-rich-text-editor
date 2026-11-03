import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {Alert, Button, ConfigProvider, Flex, Typography} from 'antd';
import 'antd/dist/reset.css';
import PersonalModelManager from '../../src/features/ainew/PersonalModelManager';
import ChatConfigurationForm from '../../src/features/ainew/ChatConfigurationForm';
import PreparedModelNotice, {useModelChatHandoff} from '../../src/features/ainew/PreparedModelNotice';
import {clearModelChatHandoff} from '../../src/features/ainew/modelUsageRecovery';
import type {ChatTestConfig} from '../../src/features/ainew/config';
import type {ChatModelOption} from '../../src/services/arte-ai/types';
import messages from '../../src/locales/zh-CN/aiChat';
import {simulatePreparationResponseLoss} from './shim';

const context = await fetch('/fixture/info').then(value => value.json());
const t = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;

function Acceptance() {
  const [chat, setChat] = useState(false);
  const [mount, setMount] = useState(0);
  const [narrow, setNarrow] = useState(false);
  const [unsafe, setUnsafe] = useState(false);
  const [selected, setSelected] = useState<{ key: string; resourceId: string; revision: number; refresh: boolean }>();
  const [config, setConfig] = useState<ChatTestConfig | null>(null);
  const [result, setResult] = useState('');
  const [facts, setFacts] = useState('');
  const {saved} = useModelChatHandoff(context);
  const apply = (value: ChatTestConfig, option: ChatModelOption) => {
    setConfig(value);
    if (saved.handoff && saved.handoff.request.resourceId === option.resourceId) clearModelChatHandoff(context, saved.handoff.id);
  };
  return <ConfigProvider>
    <main style={{maxWidth: narrow ? 390 : 1160, padding: 16, margin: 'auto', overflowWrap: 'anywhere'}}>
      <Typography.Title level={3}>模型到聊天 · A2</Typography.Title>
      <Alert type="info" title="隔离验收：正式管理／准备／配置组件、真实 MVC／H2／回环供应商。固定测试会话，不验证生产 JWT。"/>
      <Flex wrap gap={8} style={{margin: '12px 0'}}>
        <Button onClick={() => setNarrow(value => !value)}>{narrow ? '宽屏预览' : '390px 容器预览'}</Button>
        <Button onClick={simulatePreparationResponseLoss}>模拟下一次准备响应丢失</Button>
        <Button onClick={() => setMount(value => value + 1)}>重挂载并恢复原操作</Button>
        <Button
          onClick={() => void fetch('/fixture/state').then(value => value.json()).then(value => setFacts(JSON.stringify(value)))}>核对隔离数据</Button>
        <Button disabled={unsafe} onClick={() => setChat(value => !value)}>{chat ? '返回模型管理' : '打开聊天'}</Button>
      </Flex>
      {facts && <Alert title={facts}/>}
      {!chat ? <PersonalModelManager key={mount} context={context} onUpdated={() => {
        }} onUnsafeChange={setUnsafe} onUseInChat={() => setChat(true)}/>
        : <>
          <PreparedModelNotice context={context} disabled={false} onSelect={handoff => setSelected(previous => ({
            key: JSON.stringify([handoff.request.binding.id, handoff.request.binding.version]),
            resourceId: handoff.request.resourceId, revision: (previous?.revision ?? 0) + 1, refresh: true
          }))}/>
          <ChatConfigurationForm fixedScope={context.scope} restored={null} requireExplicitApply={!!saved.handoff}
                                 externalModel={selected} onApply={apply} onRestore={apply}
                                 onDirty={() => setConfig(null)} onReset={() => setConfig(null)} t={t}
                                 translate={id => messages[id as keyof typeof messages] ?? id}/>
          <Button type="primary" disabled={!config} onClick={() => void fetch('/fixture/run', {
            method: 'POST',
            body: JSON.stringify({type: 'binding', id: config!.bindingId, version: config!.bindingVersion})
          })
            .then(value => value.json()).then(value => setResult(`${value.state} · ${value.settlement.includes('SETTLED') ? 'SETTLED' : value.settlement}`))}>发送隔离聊天</Button>
          {result && <Alert type="success" title={result}/>}
        </>}
    </main>
  </ConfigProvider>;
}

createRoot(document.getElementById('root')!).render(<Acceptance/>);
