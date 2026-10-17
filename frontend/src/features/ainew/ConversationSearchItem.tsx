import React from 'react';
import {Button, Flex, Popover, Tooltip} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import type {ConversationSearchItem as SearchItem} from '@/services/arte-ai/conversation-search-contract';
import type {SearchRow} from './useConversationSearch';
import {searchExcerpt, SearchHighlight} from './searchText';

export default function ConversationSearchItem({row, disabled, busy, preview, onPreview, onOpen, narrowPreview}: {
  row: SearchRow; disabled: boolean; busy: 'message' | 'conversation' | null; preview: boolean;
  onPreview: (open: boolean) => void; onOpen: (row: SearchItem, mode: 'message' | 'conversation') => void;
  narrowPreview: boolean;
}) {
  if ('unavailable' in row) return <li className="ainew-search-row is-unavailable" data-search-key={row.entryKey}>
    <span>该结果已不可用</span>
    <Flex vertical gap={4} className="ainew-search-actions">
      <Button type="text" disabled aria-label="查看命中消息（不可用）" icon={<DynamicIcon iconName="AimOutlined"/>}/>
      <Button type="text" disabled aria-label="打开会话并退出搜索（不可用）"
              icon={<DynamicIcon iconName="LoginOutlined"/>}/>
    </Flex>
  </li>;
  return <li className="ainew-search-row" data-search-key={row.entryKey}>
    <div className="ainew-search-text">
      <div className="ainew-search-title">{row.title}</div>
      <Popover open={preview && !disabled} onOpenChange={onPreview} trigger={['hover', 'focus', 'click']}
               classNames={{root: `ainew-search-popover${narrowPreview ? ' is-narrow' : ''}`}}
               mouseEnterDelay={0.3} mouseLeaveDelay={0.15} placement={narrowPreview ? 'bottom' : 'rightTop'}
               destroyOnHidden fresh
               content={preview && !disabled ? <div className="ainew-search-preview" onKeyDown={event => {
                 if (event.key === 'Escape') {
                   event.stopPropagation();
                   onPreview(false);
                 }
               }}><SearchHighlight snippet={row.snippet}/></div> : <></>}>
        <button type="button" className="ainew-search-snippet" disabled={disabled}
                aria-label={`预览命中内容：${row.title}`}
                aria-expanded={preview} onClick={() => onPreview(true)} onKeyDown={event => {
          if (event.key === 'Escape') {
            event.stopPropagation();
            onPreview(false);
          }
        }}><SearchHighlight snippet={searchExcerpt(row.snippet)}/></button>
      </Popover>
    </div>
    <Flex vertical gap={4} className="ainew-search-actions">
      <Tooltip title={row.canLocateMessage ? '查看命中消息' : '仅会话名称匹配'}>
        <Button type="text" aria-label={`查看命中消息：${row.title}`} disabled={disabled || !row.canLocateMessage}
                loading={busy === 'message'} icon={<DynamicIcon iconName="AimOutlined"/>}
                onClick={event => {
                  event.stopPropagation();
                  onPreview(false);
                  onOpen(row, 'message');
                }}/>
      </Tooltip>
      <Tooltip title="打开会话并退出搜索">
        <Button type="text" aria-label={`打开会话并退出搜索：${row.title}`} disabled={disabled}
                loading={busy === 'conversation'} icon={<DynamicIcon iconName="LoginOutlined"/>}
                onClick={event => {
                  event.stopPropagation();
                  onPreview(false);
                  onOpen(row, 'conversation');
                }}/>
      </Tooltip>
    </Flex>
  </li>;
}
