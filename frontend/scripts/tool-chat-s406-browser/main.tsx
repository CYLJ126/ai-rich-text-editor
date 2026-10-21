import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {Button, ConfigProvider, Flex, Typography} from 'antd';
import BusinessChatPanel from '../../src/features/ainew/BusinessChatPanel';
import {toolChatContext as context} from '../../src/features/ainew/toolChat.testdata';
import type {ConversationResponse} from '../../src/services/arte-ai';
import '../../src/pages/AI/ChatNew/style.css';

const initial: ConversationResponse = {
  conversationId: 'conversation',
  title: '工具聊天验收',
  version: 0,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-20T12:00:00Z',
  updatedAt: '2026-10-20T12:00:00Z',
};
const model = {
  displayName: '固定测试模型',
  binding: {type: 'binding' as const, id: 'model', version: 'v1'},
  capability: {type: 'capability' as const, id: 'text', version: 'v1'},
  contextWindowTokens: 32768,
  limits: {
    maxInputTokens: 20000,
    maxOutputTokens: 4096,
    maxInputBytes: 1000000,
    maxOutputBytes: 1000000,
    maxTimeoutSeconds: 120,
  },
  defaults: {
    maxInputTokens: 12000,
    maxOutputTokens: 128,
    timeoutSeconds: 120,
  },
  budgetRefs: ['wallet'],
  toolCallsSupported: true,
};

function App() {
  const [conversation, update] = useState(initial);
  const [workspace, setWorkspace] = useState('workspace');
  const [narrow, setNarrow] = useState(false);

  async function phase(value: string) {
    await fetch(`/fixture/${value}`, {method: 'POST'});
  }

  return (
    <ConfigProvider theme={{cssVar: {key: 'tool-chat-acceptance'}}}>
      <main
        style={{
          padding: 12,
          width: narrow ? 375 : '100%',
          maxWidth: '100%',
          height: '100dvh',
          boxSizing: 'border-box',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <Typography.Title level={4}>S4-06 工具聊天隔离验收</Typography.Title>
        <Typography.Paragraph>
          真实前端组件／HTTP 模拟服务，不连接业务库、模型或真实授权。
        </Typography.Paragraph>
        <Flex gap={8} wrap>
          <Button onClick={() => setNarrow(v => !v)}>切换 375px 窄栏</Button>
          {['approval', 'unknown', 'success', 'running', 'reset'].map(
            (value) => (
              <Button key={value} onClick={() => void phase(value)}>
                测试：{value}
              </Button>
            ),
          )}
          <Button
            onClick={() =>
              setWorkspace((v) => (v === 'workspace' ? 'other' : 'workspace'))
            }
          >
            切换测试空间
          </Button>
        </Flex>
        <BusinessChatPanel
          key={workspace}
          context={{
            ...context,
            scope: {...context.scope, workspaceId: workspace},
          }}
          conversation={conversation}
          onUpdated={update}
          config={{
            ...context.scope,
            workspaceId: workspace,
            bindingId: 'model',
            bindingVersion: 'v1',
            capabilityId: 'text',
            capabilityVersion: 'v1',
            budgetRef: 'wallet',
            maxInputTokens: 12000,
            maxOutputTokens: 128,
            timeoutSeconds: 120,
            temperature: null,
            topP: null,
          }}
          dirty={false}
          options={[model]}
          t={(key) => key}
          onConfigurationRejected={() => {
          }}
          onChooseModel={() => {
          }}
          onInteraction={() => {
          }}
        />
      </main>
    </ConfigProvider>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(<App/>);
