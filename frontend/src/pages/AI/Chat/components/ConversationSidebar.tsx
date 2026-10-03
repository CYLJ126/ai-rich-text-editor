import {DeleteOutlined, EditOutlined, PlusOutlined} from '@ant-design/icons';
import {Alert, Button, Empty, Input, Space, Spin, Typography} from 'antd';
import React, {useState} from 'react';
import type {Conversation} from '@/types/ai-new/conversation';
import {i18nText as t} from '@/utils/i18n';

export default function ConversationSidebar({
                                              conversations,
                                              selectedId,
                                              loading,
                                              error,
                                              busy,
                                              lockedId,
                                              page,
                                              hasNext,
                                              onSearch,
                                              onPage,
                                              onSelect,
                                              onCreate,
                                              onRename,
                                              onDelete,
                                              onRefresh,
                                            }: {
  conversations: Conversation[];
  selectedId: string;
  loading: boolean;
  error: string | null;
  busy: boolean;
  lockedId: string;
  page: number;
  hasNext: boolean;
  onSearch: (title: string) => void;
  onPage: (page: number) => void;
  onSelect: (id: string) => void;
  onCreate: () => void;
  onRename: (item: Conversation) => void;
  onDelete: (item: Conversation) => void;
  onRefresh: () => void;
}) {
  const [search, setSearch] = useState('');
  return (
    <Space orientation="vertical" size="middle" style={{width: '100%'}}>
      <Space style={{width: '100%', justifyContent: 'space-between'}}>
        <Typography.Title level={5} style={{margin: 0}}>
          {t('app.aiNew.conversations')}
        </Typography.Title>
        <Button
          icon={<PlusOutlined aria-hidden="true"/>}
          type="primary"
          disabled={busy}
          onClick={onCreate}
        >
          {t('app.aiNew.create')}
        </Button>
      </Space>
      <Input.Search
        aria-label={t('app.aiNew.search')}
        placeholder={t('app.aiNew.search')}
        value={search}
        maxLength={256}
        allowClear
        onChange={(event) => {
          setSearch(event.target.value);
          if (!event.target.value) onSearch('');
        }}
        onSearch={(value) => onSearch(value.trim())}
      />
      {error ? (
        <Alert
          type="error"
          title={error}
          action={
            <Button size="small" onClick={onRefresh}>
              {t('app.aiNew.refresh')}
            </Button>
          }
        />
      ) : (
        <Spin spinning={loading}>
          {conversations.length === 0 ? (
            <Empty
              description={t('app.aiNew.noConversations')}
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          ) : (
            <ul style={{listStyle: 'none', padding: 0, margin: 0}}>
              {conversations.map((item) => (
                <li
                  key={item.conversationId}
                  style={{
                    display: 'flex',
                    gap: 4,
                    alignItems: 'center',
                    background:
                      selectedId === item.conversationId
                        ? 'var(--ant-color-fill-secondary)'
                        : undefined,
                    padding: '10px 6px',
                    borderRadius: 8,
                  }}
                >
                  <Button
                    type="text"
                    style={{
                      flex: 1,
                      minWidth: 0,
                      textAlign: 'left',
                      padding: '0 4px',
                    }}
                    aria-current={
                      selectedId === item.conversationId ? 'true' : undefined
                    }
                    onClick={() => onSelect(item.conversationId)}
                  >
                    <Typography.Text ellipsis title={item.title}>
                      {item.title}
                    </Typography.Text>
                  </Button>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined aria-hidden="true"/>}
                    disabled={busy || lockedId === item.conversationId}
                    aria-label={t('app.aiNew.renameItem', {
                      title: item.title,
                    })}
                    onClick={() => onRename(item)}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined aria-hidden="true"/>}
                    disabled={busy || lockedId === item.conversationId}
                    aria-label={t('app.aiNew.deleteItem', {
                      title: item.title,
                    })}
                    onClick={() => onDelete(item)}
                  />
                </li>
              ))}
            </ul>
          )}
        </Spin>
      )}
      <Space style={{width: '100%', justifyContent: 'space-between'}}>
        <Button
          size="small"
          disabled={page === 0 || loading || busy}
          onClick={() => onPage(page - 1)}
        >
          {t('app.aiNew.previous')}
        </Button>
        <Typography.Text type="secondary">
          {t('app.aiNew.page', {page: page + 1})}
        </Typography.Text>
        <Button
          size="small"
          disabled={!hasNext || loading || busy || page >= 500}
          onClick={() => onPage(page + 1)}
        >
          {t('app.aiNew.next')}
        </Button>
      </Space>
    </Space>
  );
}
