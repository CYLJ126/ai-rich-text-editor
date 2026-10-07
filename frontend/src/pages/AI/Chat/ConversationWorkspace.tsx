import {Alert, Button, Card, Empty, Form, Input, Modal, Pagination, Tag,} from 'antd';
import React, {useState} from 'react';
import {AiApiError, type AiScope} from '@/services/arte-ai';
import ChatPanel from './ChatPanel';
import type {ChatTestConfig} from './config';
import type {ConfigurationRejected} from './configurationInvalidation';
import {CONVERSATION_PAGE_SIZE, useConversations} from './useConversations';

export default function ConversationWorkspace({
                                                scope,
                                                dirty,
                                                config,
                                                t,
                                                maxInputBytes,
                                                onConfigurationRejected,
                                              }: {
  scope: AiScope | null;
  dirty: boolean;
  config: ChatTestConfig | null;
  t: (key: string) => string;
  maxInputBytes?: number;
  onConfigurationRejected?: ConfigurationRejected;
}) {
  const conversations = useConversations(scope);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ title: string }>();
  const disabled = !scope || dirty;
  const errorAlert = (error: unknown, retry?: () => void) => {
    if (!error) return null;
    const apiError = error instanceof AiApiError ? error : null;
    return (
      <Alert
        type="error"
        showIcon
        title={
          apiError
            ? `${apiError.message} (HTTP ${apiError.httpStatus}${apiError.code ? ` / ${apiError.code}` : ''})`
            : t('error.network')
        }
        description={
          apiError?.httpStatus === 401
            ? t('error.login')
            : apiError?.httpStatus === 403
              ? t('error.permission')
              : undefined
        }
        action={
          retry && (
            <Button size="small" onClick={retry} disabled={disabled}>
              {t('retry')}
            </Button>
          )
        }
      />
    );
  };
  const selected = conversations.selected;

  return (
    <>
      <Card
        title={t('conversations')}
        size="small"
        styles={{body: {minHeight: 450}}}
      >
        <div className="flex flex-col gap-3">
          <Button
            block
            disabled={disabled}
            onClick={() => {
              form.setFieldsValue({title: conversations.pendingTitle ?? ''});
              setOpen(true);
            }}
          >
            {t('createConversation')}
          </Button>
          <Button
            size="small"
            disabled={disabled}
            loading={conversations.listLoading}
            onClick={() => void conversations.loadPage(conversations.page)}
          >
            {t('refresh')}
          </Button>
          {dirty && <p className="m-0 text-xs">{t('applyDraft')}</p>}
          {errorAlert(
            conversations.listError,
            () => void conversations.loadPage(conversations.page),
          )}
          {conversations.listLoading ? (
            <p role="status">{t('loadingList')}</p>
          ) : (
            !conversations.listError &&
            (conversations.records.length ? (
              <ul
                className="m-0 list-none space-y-2 p-0"
                aria-label={t('conversations')}
              >
                {conversations.records.map((conversation) => (
                  <li key={conversation.conversationId}>
                    <button
                      type="button"
                      aria-pressed={
                        conversation.conversationId === conversations.selectedId
                      }
                      disabled={disabled}
                      onClick={() =>
                        void conversations.select(conversation.conversationId)
                      }
                      className={`w-full rounded border border-solid p-2 text-left disabled:opacity-50 ${conversation.conversationId === conversations.selectedId ? 'border-[var(--ant-color-primary)] bg-[var(--ant-color-primary-bg)]' : 'border-[var(--ant-color-border)] bg-transparent'}`}
                    >
                      <span className="block break-words font-medium">
                        {conversation.title}
                      </span>
                      <span className="block break-all text-xs text-[var(--ant-color-text-secondary)]">
                        {conversation.conversationId}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="py-12">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={t(
                    scope ? 'conversationsEmpty' : 'messagesEmpty',
                  )}
                />
              </div>
            ))
          )}
          <Pagination
            size="small"
            simple
            current={conversations.page}
            total={conversations.total}
            pageSize={CONVERSATION_PAGE_SIZE}
            showSizeChanger={false}
            hideOnSinglePage
            disabled={disabled || conversations.listLoading}
            onChange={(page) => void conversations.loadPage(page)}
          />
        </div>
      </Card>
      <Card
        title={t('messages')}
        size="small"
        className="min-w-0"
        extra={
          <Tag>{t(selected ? 'conversationSelected' : 'noConversation')}</Tag>
        }
        styles={{
          body: {
            minHeight: 450,
            display: 'flex',
            flexDirection: 'column',
            gap: 16,
          },
        }}
      >
        <div className="min-h-56 flex-1" aria-live="polite">
          {conversations.detailLoading ? (
            <p role="status">{t('loadingDetail')}</p>
          ) : conversations.detailError ? (
            errorAlert(conversations.detailError, () => {
              if (conversations.selectedId)
                void conversations.select(conversations.selectedId);
            })
          ) : selected ? (
            <>
              <h2 className="mt-0 break-words text-base font-semibold">
                {selected.title}
              </h2>
              <dl
                className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm"
                aria-label={t('conversationDetail')}
              >
                <dt>{t('conversationId')}</dt>
                <dd className="m-0 break-all">{selected.conversationId}</dd>
                <dt>{t('conversationVersion')}</dt>
                <dd className="m-0">{selected.version}</dd>
                <dt>{t('conversationState')}</dt>
                <dd className="m-0">{selected.state}</dd>
                <dt>{t('createdAt')}</dt>
                <dd className="m-0 break-all">{selected.createdAt}</dd>
                <dt>{t('updatedAt')}</dt>
                <dd className="m-0 break-all">{selected.updatedAt}</dd>
              </dl>
            </>
          ) : (
            <div className="flex min-h-56 items-center justify-center">
              <Empty
                description={t(scope ? 'messagesConfigured' : 'messagesEmpty')}
              />
            </div>
          )}
        </div>
        {selected && config ? (
          <ChatPanel
            key={selected.conversationId}
            config={config}
            conversation={selected}
            dirty={dirty}
            onUpdated={conversations.updateConversation}
            t={t}
            maxInputBytes={maxInputBytes}
            onConfigurationRejected={onConfigurationRejected}
          />
        ) : (
          <>
            <div className="flex flex-wrap gap-2 text-[var(--ant-color-text-secondary)]">
              <span>{t('invocationState')}：—</span>
              <span>
                {t('budgetAvailable')}：{t('notQueried')}
              </span>
            </div>
            <Input.TextArea
              disabled
              aria-label={t('messageInput')}
              autoSize={{minRows: 3, maxRows: 6}}
              placeholder={t('messagePlaceholder')}
            />
            <div className="flex justify-end">
              <Button type="primary" disabled>
                {t('send')}
              </Button>
            </div>
          </>
        )}
      </Card>
      <Modal
        title={t('createConversation')}
        open={open}
        forceRender
        confirmLoading={conversations.creating}
        okText={t(conversations.pendingTitle ? 'retryCreate' : 'create')}
        cancelText={t('cancel')}
        okButtonProps={{disabled}}
        cancelButtonProps={{disabled: conversations.creating}}
        closable={!conversations.creating}
        mask={{closable: false}}
        keyboard={!conversations.creating}
        onOk={() => form.submit()}
        onCancel={() => setOpen(false)}
      >
        <Form
          form={form}
          layout="vertical"
          aria-label={t('createConversation')}
          onFinish={async ({title}) => {
            if (disabled) return;
            if (await conversations.create(title.trim())) {
              setOpen(false);
              form.resetFields();
            }
          }}
        >
          <Form.Item
            name="title"
            label={t('conversationTitle')}
            rules={[
              {
                required: true,
                whitespace: true,
                message: t('validation.required'),
              },
              {max: 256, message: t('validation.maxLength')},
            ]}
          >
            <Input
              maxLength={256}
              disabled={
                conversations.pendingTitle !== null || conversations.creating
              }
            />
          </Form.Item>
          {errorAlert(conversations.createError)}
          {conversations.pendingTitle && <p>{t('createRetryHint')}</p>}
        </Form>
      </Modal>
    </>
  );
}
