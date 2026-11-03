import React, {useEffect, useMemo, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {Alert, Button, Card, ConfigProvider, Flex, Select, Typography} from 'antd';
import 'antd/dist/reset.css';
import Models from '../../src/pages/AI/Models';
import Workspaces from '../../src/pages/Environment/Workspaces';
import WorkspaceAiUsagePanel from '../../src/features/ainew/WorkspaceAiUsagePanel';
import ChatConfigurationForm from '../../src/features/ainew/ChatConfigurationForm';
import {useCurrentWorkspace} from '../../src/features/workspace/useWorkspace';
import type {ChatTestConfig} from '../../src/features/ainew/config';
import messages from '../../src/locales/zh-CN/aiChat';
import {
  fixtureFetch,
  history,
  login,
  setFixtureSession,
  simulateConfirmedReadFailure,
  simulateResponseLoss,
  useModel
} from './shim';

const info = await fixtureFetch('/fixture/info');

function Chat() {
  const {initialState} = useModel();
  const userId = initialState.currentUser.id;
  const workspace = useCurrentWorkspace(userId);
  const [config, setConfig] = useState<ChatTestConfig | null>(null);
  const [result, setResult] = useState('');
  const [unsafe, setUnsafe] = useState(false);
  const [revision, setRevision] = useState(0);
  const context = useMemo(() => workspace ? {
    userId,
    scope: workspace.scope
  } : null, [userId, workspace?.scope.tenantId, workspace?.scope.workspaceId]);
  if (!context) return <Alert title="请先通过正式工作空间目录选择聊天空间。"/>;
  return <Flex vertical gap={12}>
    <Typography.Title level={3}>隔离聊天 · 当前空间 {workspace?.name}</Typography.Title>
    <WorkspaceAiUsagePanel key={JSON.stringify(context)} context={context} disabled={false} onUnsafeChange={setUnsafe}
                           onPrepared={() => setRevision(value => value + 1)} onNavigate={proceed => proceed()}/>
    <ChatConfigurationForm key={JSON.stringify(context)} fixedScope={context.scope}
                           requireExplicitApply restored={null} refreshRevision={revision}
                           onDirty={() => setConfig(null)} onApply={value => {
      setConfig(value);
    }} onRestore={() => {
    }}
                           onReset={() => setConfig(null)}
                           t={key => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key}
                           translate={id => messages[id as keyof typeof messages] ?? id}/>
    <Button type="primary" disabled={!config || unsafe} onClick={() => {
      if (config) void fixtureFetch('/fixture/run', {
        scope: context.scope,
        binding: {type: 'binding', id: config.bindingId, version: config.bindingVersion},
        budgetRef: config.budgetRef,
        maxOutputTokens: config.maxOutputTokens
      })
        .then(value => setResult(`${value.state} · ${JSON.parse(value.settlement).body.state === 'SETTLED' ? 'SETTLED' : 'UNSETTLED'} · 主体 ${value.subject} · 空间 ${value.scope.workspaceId}`))
        .catch(failure => setResult(failure.message));
    }}>发送真实回环聊天</Button>
    {result && <Alert type={result.startsWith('SUCCEEDED · SETTLED ·') ? 'success' : 'warning'} title={result}/>}
  </Flex>;
}

function Acceptance() {
  const [location, setLocation] = useState(history.location);
  const [facts, setFacts] = useState('');
  const [mount, setMount] = useState(0);
  const {initialState} = useModel();
  useEffect(() => history.listen(({location: next}) => setLocation(next)), []);
  return <ConfigProvider><Alert type="info"
                                title="M5 隔离验收：正式页面／MVC／授权／全新 H2／回环供应商；固定测试会话，非生产 JWT。"/>
    <Flex wrap gap={8} style={{padding: 12}}>
      <Button onClick={() => history.push('/Environment/Models')}>模型配置页面</Button>
      <Button onClick={() => history.push('/Environment/Workspaces?tab=ai')}>工作空间配置页面</Button>
      <Button onClick={() => history.push('/AI/ChatNew')}>隔离聊天</Button>
      <Select aria-label="隔离测试角色" value={login} options={['alice', 'publisher', 'bob'].map(value => ({
        value,
        label: value === 'alice' ? '平台与空间负责人' : value === 'publisher' ? '普通模型发布者' : '执行空间普通用户'
      }))}
              onChange={value => {
                setFixtureSession(value, String(info.users[value]));
                setConfigRoute();
              }}/>
      <Button onClick={() => simulateResponseLoss('/prepareModelUsage')}>丢失下一次本人准备响应</Button>
      <Button onClick={() => simulateResponseLoss('/updateConfiguration')}>丢失下一次空间保存响应</Button>
      <Button onClick={simulateConfirmedReadFailure}>空间保存成功后重读失败</Button>
      <Button onClick={() => setMount(value => value + 1)}>重挂载当前页</Button>
      <Button
        onClick={() => void fixtureFetch('/fixture/state').then(value => setFacts(JSON.stringify(value)))}>核对隔离数据</Button>
    </Flex>
    <Card size="small"><Typography.Text>回环地址：{info.origin}/v1 · 测试密钥：fixture-model-key · 目标
      B：{info.target.workspaceId}</Typography.Text>
      <Typography.Paragraph
        style={{overflowWrap: 'anywhere'}}>普通用户主体：{info.subjects.bob}；发布者主体：{info.subjects.publisher}</Typography.Paragraph></Card>
    {facts && <pre style={{whiteSpace: 'pre-wrap', overflowWrap: 'anywhere'}}>{facts}</pre>}
    <div key={`${initialState.currentUser.id}-${mount}`}>
      {location.pathname === '/Environment/Workspaces' ? <Workspaces/> : location.pathname === '/AI/ChatNew' ? <Chat/> :
        <Models/>}
    </div>
  </ConfigProvider>;
}

function setConfigRoute() {
  history.push('/Environment/Models');
}

const root = document.getElementById('root');
if (!root) throw new Error('隔离验收挂载节点不存在');
createRoot(root).render(<Acceptance/>);
