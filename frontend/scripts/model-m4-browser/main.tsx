import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {Alert, Button, ConfigProvider, Flex} from 'antd';
import 'antd/dist/reset.css';
import Models from '../../src/pages/AI/Models';
import Workspaces from '../../src/pages/Environment/Workspaces';
import {fixtureFacts, history} from './shim';

function Fixture() {
  const [location, setLocation] = useState(history.location);
  const [facts, setFacts] = useState('');
  useEffect(() => history.listen(({location: next}) => setLocation(next)), []);
  return <ConfigProvider><Alert type="info" title="M4 页面布局与契约夹具：所有接口为内存模拟，不验证后端授权或真实聊天。"/>
    <Flex wrap gap={8} style={{padding: 8}}>
      <Button onClick={() => history.push('/Environment/Models')}>模型配置页面</Button>
      <Button onClick={() => history.push('/Environment/Workspaces?tab=ai')}>工作空间配置页面</Button>
      <Button onClick={() => setFacts(JSON.stringify(fixtureFacts()))}>查看内存状态</Button>
    </Flex>{facts && <pre style={{whiteSpace: 'pre-wrap', overflowWrap: 'anywhere'}}>{facts}</pre>}
    {location.pathname === '/Environment/Models' ? <Models/> : <Workspaces/>}
  </ConfigProvider>;
}

createRoot(document.getElementById('root')!).render(<Fixture/>);
