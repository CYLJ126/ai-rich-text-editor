import {ConfigProvider, Typography} from 'antd';
import React from 'react';
import {createRoot} from 'react-dom/client';
import ToolWorkspace from '../../src/features/aitool/ToolWorkspace';

const fixture = await fetch('/arte/ai-new/fixture').then((response) =>
  response.json(),
);
const root = document.getElementById('root');
if (!root) throw new Error('Acceptance root missing');
createRoot(root).render(
  <ConfigProvider theme={{cssVar: {key: 'tool-platform-acceptance'}}}>
    <main className="tool-page">
      <Typography.Title level={3}>AI 工具</Typography.Title>
      <Typography.Paragraph type="secondary">
        S2-09 本机验收 · 固定测试会话 · 正式工具／平台授权／独立 MySQL
      </Typography.Paragraph>
      <ToolWorkspace userId={fixture.userId} workspace={fixture.workspace}/>
    </main>
  </ConfigProvider>,
);
