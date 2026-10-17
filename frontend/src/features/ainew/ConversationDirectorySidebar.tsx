import React, {useState} from 'react';
import {Button, Flex, Input} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import type {ConversationDirectory} from './useConversationDirectory';
import ConversationDirectoryList from './ConversationDirectoryList';
import type {ConversationSearch} from './useConversationSearch';
import type {ConversationSearchItem} from '@/services/arte-ai';
import ConversationSearchResults from './ConversationSearchResults';

export default function ConversationDirectorySidebar({
                                                       directory,
                                                       writable,
                                                       readBlocked,
                                                       onOpen,
                                                       onCreate,
                                                       header,
                                                       search: controller,
                                                       onSearchOpen
                                                     }: {
  directory: ConversationDirectory; writable: boolean; readBlocked: boolean;
  onOpen: (id: string) => void; onCreate: () => void; header?: React.ReactNode;
  search?: ConversationSearch; onSearchOpen?: (row: ConversationSearchItem, mode: 'message' | 'conversation') => void;
}) {
  const [search, setSearch] = useState('');

  return <Flex component="aside" vertical gap={12} className="ainew-sidebar ainew-directory" aria-label="会话列表">
    <Flex vertical gap={12} className="ainew-directory-top">
      {header ?? <Button block type="primary" disabled={readBlocked || !writable || directory.creating}
                         loading={directory.creating}
                         onClick={onCreate}>{directory.hasPendingCreate ? '确认原创建操作' : '新建会话'}</Button>}
      <Input aria-label="搜索会话" placeholder={controller ? '搜索会话或消息' : '搜索（暂未开放）'}
             value={controller?.input ?? search} disabled={readBlocked}
             allowClear prefix={<DynamicIcon iconName="SearchOutlined"/>}
             onChange={event => controller ? controller.change(event.target.value) : setSearch(event.target.value)}
             onClear={() => {
               if (controller) void controller.exit();
             }}
             onCompositionStart={() => controller?.composition(true)}
             onCompositionEnd={event => controller?.composition(false, event.currentTarget.value)}
             onKeyDown={event => {
               if (event.key === 'Enter' && !event.nativeEvent.isComposing && event.keyCode !== 229) {
                 event.preventDefault();
                 void controller?.submit();
               }
             }}/>
    </Flex>
    {controller?.active ? <ConversationSearchResults search={controller} onOpen={onSearchOpen ?? (() => {
      })}/>
      :
      <ConversationDirectoryList directory={directory} writable={writable} readBlocked={readBlocked} onOpen={onOpen}/>}
    {!controller?.active && controller?.error && <p role="alert">{controller.error}</p>}
  </Flex>;
}
