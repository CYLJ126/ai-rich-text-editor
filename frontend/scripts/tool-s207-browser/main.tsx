import React from 'react';
import {createRoot} from 'react-dom/client';
import {ConfigProvider, Typography} from 'antd';
import ToolWorkspace from '../../src/features/aitool/ToolWorkspace';

createRoot(document.getElementById('root')!).render(
  <ConfigProvider theme={{cssVar: {key: 'tool-acceptance'}}}>
    <main className="tool-page">
      <Typography.Title level={3}>AI 工具</Typography.Title>
      <Typography.Paragraph type="secondary">
        S2-07 隔离验收 · 固定测试身份与空间 · 真实 MVC／H2 · 不执行工具
      </Typography.Paragraph>
      <ToolWorkspace
        userId="tool-browser-alice"
        workspace={{
          scope: {tenantId: 'tenant', workspaceId: 'workspace'},
          name: '验收空间',
          state: 0,
          currentRole: 0,
          version: 0,
        }}
      />
    </main>
  </ConfigProvider>,
);
