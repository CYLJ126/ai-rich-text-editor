import {RobotOutlined} from '@ant-design/icons';
import {Alert, App, Button, Card, Descriptions, Empty, Result, Space, Spin, Tag, Typography,} from 'antd';
import {createStyles} from 'antd-style';
import React, {useEffect, useRef, useState} from 'react';
import {chatErrorText, isAccessError} from '@/features/ai-chat/errors';
import {
  CONVERSATION_PAGE_SIZE,
  useConversation,
  useConversationCommands,
  useConversations,
} from '@/features/ai-chat/hooks/useConversations';
import type {ChatBootstrap, Conversation, WorkspaceSelection,} from '@/types/ai-new/conversation';
import {i18nText as t} from '@/utils/i18n';
import {navigate} from '../navigation';
import ConversationDialog from './ConversationDialog';
import ConversationSidebar from './ConversationSidebar';

const useStyles = createStyles(({css, token}) => ({
  layout: css`display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 20px;
    @media (max-width: 900px) { grid-template-columns: minmax(0, 1fr); }`,
  sidebar: css`background: ${token.colorBgContainer}; border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px; padding: 20px; min-width: 0;`,
  content: css`min-width: 0; display: flex; flex-direction: column; gap: 16px;`,
}));
export default function ConversationWorkspace({
                                                userId,
                                                scope,
                                                bootstrap,
                                                selectedId,
                                              }: {
  userId: string;
  scope: WorkspaceSelection;
  bootstrap: ChatBootstrap;
  selectedId: string;
}) {
  const {styles} = useStyles();
  const {message, modal} = App.useApp();
  const active = useRef(true);
  const confirmations = useRef(new Set<ReturnType<typeof modal.confirm>>());
  useEffect(() => {
    active.current = true;
    const handles = confirmations.current;
    return () => {
      active.current = false;
      for (const handle of handles) handle.destroy();
      handles.clear();
    };
  }, []);
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<{ conversation?: Conversation } | null>(
    null,
  );
  const list = useConversations(userId, scope, filter, page);
  const detail = useConversation(userId, scope, selectedId);
  const commands = useConversationCommands(userId, scope);
  const model = bootstrap.defaultModel;
  const items = list.error
    ? []
    : (list.data ?? []).slice(0, CONVERSATION_PAGE_SIZE);
  useEffect(() => {
    if (page > 0 && list.isSuccess && list.data.length === 0) setPage(page - 1);
  }, [page, list.isSuccess, list.data]);
  const save = async (title: string) => {
    if (commands.busy) return;
    try {
      const result = dialog?.conversation
        ? await commands.rename.mutateAsync({
          conversation: dialog.conversation,
          title,
        })
        : await commands.create.mutateAsync(title);
      if (!active.current) return;
      setDialog(null);
      navigate(scope, result.conversationId);
      void message.success(t('app.aiNew.saved'));
    } catch (error) {
      if (!active.current) return;
      setDialog(null);
      void message.error(chatErrorText(error));
    }
  };
  const remove = (conversation: Conversation) => {
    const confirmation = modal.confirm({
      title: t('app.aiNew.deleteConfirm'),
      content: t('app.aiNew.deleteDescription', {title: conversation.title}),
      okText: t('app.aiNew.delete'),
      cancelText: t('app.aiNew.cancel'),
      okButtonProps: {danger: true},
      onCancel: () => confirmations.current.delete(confirmation),
      onOk: async () => {
        try {
          await commands.remove.mutateAsync(conversation);
          if (!active.current) return;
          if (selectedId === conversation.conversationId) navigate(scope);
          void message.success(t('app.aiNew.deleted'));
        } catch (error) {
          if (active.current) void message.error(chatErrorText(error));
        } finally {
          confirmations.current.delete(confirmation);
        }
      },
    });
    confirmations.current.add(confirmation);
  };
  const accessError = isAccessError(list.error) || isAccessError(detail.error);
  if (accessError)
    return (
      <Result
        status="403"
        title={t('app.aiNew.noAccess')}
        subTitle={t('app.aiNew.error.access')}
      />
    );
  return (
    <div className={styles.layout}>
      <aside
        className={styles.sidebar}
        aria-label={t('app.aiNew.conversations')}
      >
        <ConversationSidebar
          conversations={items}
          selectedId={selectedId}
          loading={list.isFetching}
          error={list.error ? chatErrorText(list.error) : null}
          busy={commands.busy}
          page={page}
          hasNext={(list.data?.length ?? 0) > CONVERSATION_PAGE_SIZE}
          onSearch={(value) => {
            setFilter(value);
            setPage(0);
          }}
          onPage={setPage}
          onSelect={(id) => navigate(scope, id)}
          onCreate={() => setDialog({})}
          onRename={(conversation) => setDialog({conversation})}
          onDelete={remove}
          onRefresh={() => {
            void list.refetch();
          }}
        />
      </aside>
      <section className={styles.content} aria-label={t('app.aiNew.details')}>
        <Card>
          {detail.error ? (
            <Alert
              type="error"
              title={chatErrorText(detail.error)}
              action={
                <Button
                  onClick={() => {
                    void detail.refetch();
                  }}
                >
                  {t('app.aiNew.refresh')}
                </Button>
              }
            />
          ) : selectedId && detail.isPending ? (
            <Spin/>
          ) : detail.data ? (
            <>
              <Space
                style={{
                  width: '100%',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                }}
              >
                <Typography.Title
                  level={3}
                  style={{margin: 0, overflowWrap: 'anywhere'}}
                >
                  {detail.data.title}
                </Typography.Title>
                <Space>
                  <Button
                    disabled={commands.busy}
                    onClick={() => setDialog({conversation: detail.data})}
                  >
                    {t('app.aiNew.rename')}
                  </Button>
                  <Button
                    danger
                    disabled={commands.busy}
                    onClick={() => remove(detail.data)}
                  >
                    {t('app.aiNew.delete')}
                  </Button>
                </Space>
              </Space>
              <Descriptions
                size="small"
                column={1}
                style={{marginTop: 24}}
                items={[
                  {
                    key: 'status',
                    label: t('app.aiNew.status'),
                    children: <Tag color="green">{t('app.aiNew.active')}</Tag>,
                  },
                  {
                    key: 'created',
                    label: t('app.aiNew.created'),
                    children: new Date(detail.data.createdAt).toLocaleString(),
                  },
                  {
                    key: 'updated',
                    label: t('app.aiNew.updated'),
                    children: new Date(detail.data.updatedAt).toLocaleString(),
                  },
                ]}
              />
              <Empty
                style={{margin: '48px 0'}}
                description={t('app.aiNew.ready')}
              />
            </>
          ) : (
            <Empty
              style={{margin: '48px 0'}}
              description={t('app.aiNew.chooseConversation')}
            />
          )}
        </Card>
        {model && (
          <Card
            title={
              <Space>
                <RobotOutlined/>
                {t('app.aiNew.defaultModel')}
              </Space>
            }
          >
            <Descriptions
              size="small"
              column={1}
              items={[
                {
                  key: 'name',
                  label: t('app.aiNew.modelName'),
                  children: model.name,
                },
                {
                  key: 'provider',
                  label: t('app.aiNew.provider'),
                  children: new URL(model.destination).host,
                },
                {
                  key: 'version',
                  label: t('app.aiNew.bindingVersion'),
                  children: model.bindingRef.version,
                },
                {
                  key: 'destination',
                  label: t('app.aiNew.destination'),
                  children: (
                    <Typography.Text style={{overflowWrap: 'anywhere'}}>
                      {model.destination}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'purpose',
                  label: t('app.aiNew.purpose'),
                  children: t('app.aiNew.textGeneration'),
                },
                {
                  key: 'limits',
                  label: t('app.aiNew.limits'),
                  children: t('app.aiNew.limitsValue', {
                    bytes: model.contextMaxBytes,
                    tokens: model.maxOutputTokens,
                  }),
                },
              ]}
            />
          </Card>
        )}
      </section>
      <ConversationDialog
        open={dialog !== null}
        initialTitle={dialog?.conversation?.title ?? ''}
        renaming={Boolean(dialog?.conversation)}
        busy={commands.busy}
        onCancel={() => setDialog(null)}
        onSave={save}
      />
    </div>
  );
}
