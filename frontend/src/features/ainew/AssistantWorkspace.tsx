import {Alert, Button, Collapse, Empty, Flex, Input, Popconfirm, Spin, Tag, Typography} from 'antd';
import {useState} from 'react';
import {type AssistantSummary, listUsableAssistants} from '@/services/arte-ai/assistant';
import {AiApiError} from '@/services/arte-ai/request';
import AssistantManagement from './AssistantManagement';
import {assistantError, useAssistantRead} from './assistantState';
import type {BrowserContext} from './storage';

export default function AssistantWorkspace({context, writable, selectionDisabled, onUse, onUnsafeChange, onUpdated}: {
  context: BrowserContext;
  writable: boolean;
  selectionDisabled: boolean;
  onUse: (assistant: AssistantSummary, reload: () => void) => void;
  onUnsafeChange: (unsafe: boolean) => void;
  onUpdated: () => void;
}) {
  const [management, setManagement] = useState(false);
  const [opened, setOpened] = useState(false);
  const [cursor, setCursor] = useState<string | null>(null);
  const [name, setName] = useState('');
  const directory = useAssistantRead(JSON.stringify([cursor, name]), signal =>
    listUsableAssistants({scope: context.scope, cursor, limit: 15, name}, {signal}));
  const featureOff = directory.error instanceof AiApiError && directory.error.httpStatus === 404;

  function reload() {
    setCursor(null);
    directory.reload();
  }

  return <Flex vertical gap={12}>
    <Flex gap={8} align="center" justify="space-between" wrap>
      <Typography.Text strong>此空间可用助手</Typography.Text>
      <Button disabled={featureOff} onClick={() => {
        setOpened(true);
        setManagement(true);
      }}>管理助手</Button>
    </Flex>
    <Input.Search aria-label="按助手名称搜索" placeholder="按助手名称搜索" allowClear maxLength={160}
                  onSearch={value => {
                    setName(Array.from(value.trim()).slice(0, 80).join(''));
                    reload();
                  }}/>
    {directory.loading && <Spin size="small"/>}
    {!!directory.error && <Alert type={featureOff ? 'info' : 'warning'}
                                 title={featureOff ? '当前环境尚未启用助手功能。' : assistantError(directory.error)}
                                 action={<Button size="small" onClick={reload}>重读目录</Button>}/>}
    {directory.value?.items.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                                                   description={name ? '未找到匹配的可用助手。' : '当前没有可选用的已发布助手。'}/>}
    {directory.value && <Flex vertical gap={12}>
      {directory.value.items.map(item => <Flex vertical gap={6} key={item.assistantId}
                                               style={{overflowWrap: 'anywhere'}}>
        <Flex align="center" gap={6} wrap>
          <Typography.Text strong>{item.displayName}</Typography.Text><Tag>v{item.chatProfile.version}</Tag>
        </Flex>
        <Typography.Text>{item.description}</Typography.Text>
        {!!item.examplePrompts.length && <Collapse size="small" items={[{
          key: 'examples', label: '示例问题', children:
            <ol>{item.examplePrompts.map((example, index) => <li key={index}>{example}</li>)}</ol>
        }]}/>}
        <Popconfirm title={'用「' + item.displayName + '」v' + item.chatProfile.version + '开启新会话？'}
                    description="此会话固定使用选定的发布版本。" okText="确认开启" cancelText="取消"
                    disabled={!writable || selectionDisabled} onConfirm={() => onUse(item, reload)}>
          <Button disabled={!writable || selectionDisabled}>使用此版本开启会话</Button>
        </Popconfirm>
      </Flex>)}
    </Flex>}
    {(cursor || directory.value?.nextCursor) && <Flex gap={8}>
      <Button size="small" disabled={!cursor} onClick={() => setCursor(null)}>首页</Button>
      <Button size="small" disabled={!directory.value?.nextCursor}
              onClick={() => setCursor(directory.value?.nextCursor ?? null)}>下一页</Button>
    </Flex>}
    {opened &&
      <AssistantManagement context={context} open={management} writable={writable} onClose={() => setManagement(false)}
                           onUnsafeChange={onUnsafeChange} onUpdated={() => {
        reload();
        onUpdated();
      }}/>}
  </Flex>;
}
