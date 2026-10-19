import React from 'react';
import {createRoot} from 'react-dom/client';
import {ConfigProvider, Typography} from 'antd';
import ToolRunPanel from '../../src/features/aitool/ToolRunPanel';
import '../../src/features/aitool/tools.css';

const {binding} = await fetch('/arte/ai-new/fixture').then(response => response.json());
const noop = () => {
};
createRoot(document.getElementById('root')!).render(
  <ConfigProvider theme={{cssVar: {key: 'tool-run-acceptance'}}}>
    <main className="tool-page">
      <Typography.Title level={3}>AI 工具调用</Typography.Title>
      <Typography.Paragraph type="secondary">S2-08 隔离验收 · 固定测试身份 · 真实 MVC／H2／Worker</Typography.Paragraph>
      <ToolRunPanel context={{userId: 'tool-run-browser-alice', scope: {tenantId: 'tenant', workspaceId: 'workspace'}}}
                    bindings={[binding]} selectedBindingId={binding.bindingId} onDirty={noop}/>
    </main>
  </ConfigProvider>,
);
