import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {Button, ConfigProvider, Flex, Tabs, Tooltip, Typography} from 'antd';
import {
  AppstoreOutlined,
  FileWordOutlined,
  FunctionOutlined,
  GoldOutlined,
  HomeOutlined,
  MessageOutlined,
  RobotOutlined
} from '@ant-design/icons';
import BusinessChatPanel from '@/features/ainew/BusinessChatPanel';
import ChatWorkspaceLayout from '@/features/ainew/ChatWorkspaceLayout';
import {useChatLayout} from '@/features/ainew/useChatLayout';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import {toolChatContext as context} from '@/features/ainew/toolChat.testdata';
import type {ConversationResponse} from '@/services/arte-ai';
import '@/pages/AI/ChatNew/style.css';

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
  const [tab, setTab] = useState('models');
  const [toolSettingsContainer, setToolSettingsContainer] = useState<HTMLDivElement | null>(null);
  const layout = useChatLayout();

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
        <div className="ainew-workspace">
          <ChatWorkspaceLayout layout={layout} list={<Typography.Text>测试会话列表</Typography.Text>}
                               settings={<Flex vertical className="ainew-settings">
                                 <Flex justify="space-between">
                                   <Typography.Text strong>设置与管理</Typography.Text>
                                   <Button onClick={() => layout.close('settings')}>收起侧栏</Button>
                                 </Flex>
                                 <Tabs className={`ainew-settings-tabs${tab === 'workspace' ? ' is-workspace' : ''}`}
                                       tabBarGutter={0}
                                       activeKey={tab} onChange={setTab} destroyOnHidden={false} items={[
                                   {
                                     key: 'workspace',
                                     className: 'ainew-workspace-pane',
                                     label: '',
                                     icon: <Tooltip title="工作空间"><HomeOutlined aria-label="工作空间"/></Tooltip>,
                                     forceRender: true,
                                     children: <WorkspacePicker userId="workspace-scroll-fixture"
                                                                presentation="inline"/>,
                                   },
                                   {
                                     key: 'settings',
                                     label: '',
                                     icon: <Tooltip title="会话设置"><MessageOutlined aria-label="会话设置"/></Tooltip>
                                   },
                                   {
                                     key: 'models',
                                     label: '',
                                     icon: <Tooltip title="模型管理"><AppstoreOutlined
                                       aria-label="模型管理"/></Tooltip>,
                                     children: <Typography.Text>固定测试模型</Typography.Text>
                                   },
                                   {
                                     key: 'assistants',
                                     label: '',
                                     icon: <Tooltip title="助手管理"><RobotOutlined aria-label="助手管理"/></Tooltip>
                                   },
                                   {
                                     key: 'workspace-ai',
                                     label: '',
                                     icon: <Tooltip title="空间 AI 配置"><GoldOutlined
                                       aria-label="空间 AI 配置"/></Tooltip>
                                   },
                                   {
                                     key: 'ai-tool',
                                     label: '',
                                     icon: <Tooltip title="工具配置"><FunctionOutlined
                                       aria-label="工具配置"/></Tooltip>,
                                     children: <div ref={setToolSettingsContainer}/>
                                   },
                                   {
                                     key: 'knowledge',
                                     label: '',
                                     icon: <Tooltip title="知识库引用"><FileWordOutlined
                                       aria-label="知识库引用"/></Tooltip>
                                   },
                                 ]}/>
                               </Flex>}>
            <Flex vertical className="ainew-main">
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
                onOpenToolSettings={() => {
                  setTab('ai-tool');
                  layout.open('settings');
                }}
                toolSettingsContainer={toolSettingsContainer}
                toolSettingsActive={layout.visible.settings && tab === 'ai-tool'}
              />
            </Flex>
          </ChatWorkspaceLayout>
        </div>
      </main>
    </ConfigProvider>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(<App/>);
