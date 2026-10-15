import React, {useState} from 'react';
import {Button, Flex, Input} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import type {ConversationDirectory} from './useConversationDirectory';
import ConversationDirectoryList from './ConversationDirectoryList';

export default function ConversationDirectorySidebar({directory, writable, readBlocked, onOpen, onCreate, header}: {
  directory: ConversationDirectory; writable: boolean; readBlocked: boolean;
  onOpen: (id: string) => void; onCreate: () => void; header?: React.ReactNode;
}) {
  const [search, setSearch] = useState('');

  return <Flex component="aside" vertical gap={12} className="ainew-sidebar ainew-directory" aria-label="会话列表">
    <Flex vertical gap={12} className="ainew-directory-top">
      {header ?? <Button block type="primary" disabled={readBlocked || !writable || directory.creating}
                         loading={directory.creating}
                         onClick={onCreate}>{directory.hasPendingCreate ? '确认原创建操作' : '新建会话'}</Button>}
      <Input aria-label="搜索会话" placeholder="搜索（暂未开放）" value={search} disabled={readBlocked}
             allowClear prefix={<DynamicIcon iconName="SearchOutlined"/>}
             onChange={event => setSearch(event.target.value)}/>
    </Flex>
    <ConversationDirectoryList directory={directory} writable={writable} readBlocked={readBlocked} onOpen={onOpen}/>
  </Flex>;
}
