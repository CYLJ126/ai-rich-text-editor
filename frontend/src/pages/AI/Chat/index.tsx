import {PageContainer} from '@ant-design/pro-components';
import {useQueryClient} from '@tanstack/react-query';
import {useAccess, useLocation, useModel} from '@umijs/max';
import {Alert, Button, Result, Select, Space, Spin, Typography} from 'antd';
import React, {useEffect, useRef} from 'react';
import {chatErrorText} from '@/features/ai-chat/errors';
import {useChatBootstrap} from '@/features/ai-chat/hooks/useConversations';
import {chatKeys} from '@/features/ai-chat/queryKeys';
import {i18nText as t} from '@/utils/i18n';
import ConversationWorkspace from './components/ConversationWorkspace';
import {clearPendingChatCommands} from '@/features/ai-chat/pendingCommands';
import {navigate, scopeValue} from './navigation';

export default function ChatPage() {
  const {initialState} = useModel('@@initialState');
  const access = useAccess();
  const location = useLocation();
  const client = useQueryClient();
  const userId = String(
    initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '',
  );
  const previousUser = useRef(userId);
  useEffect(() => {
    const old = previousUser.current;
    if (old && old !== userId) {
      clearPendingChatCommands();
      void client.cancelQueries({queryKey: chatKeys.user(old)});
      client.removeQueries({queryKey: chatKeys.user(old)});
    }
    previousUser.current = userId;
  }, [client, userId]);
  const bootstrap = useChatBootstrap(
    userId,
    Boolean(userId && access.canViewAiChat),
  );
  const params = new URLSearchParams(location.search);
  const tenant = params.get('tenantId');
  const workspace = params.get('workspaceId');
  const spaces = bootstrap.error ? [] : (bootstrap.data?.workspaces ?? []);
  const scope =
    tenant || workspace
      ? spaces.find(
        (item) => item.tenantId === tenant && item.workspaceId === workspace,
      )
      : spaces[0];
  let content: React.ReactNode;
  if (!userId || !access.canViewAiChat)
    content = <Result status="403" title={t('app.aiNew.noAccess')}/>;
  else if (bootstrap.error)
    content = (
      <Result
        status="error"
        title={chatErrorText(bootstrap.error)}
        extra={
          <Button
            onClick={() => {
              void bootstrap.refetch();
            }}
          >
            {t('app.aiNew.refresh')}
          </Button>
        }
      />
    );
  else if (bootstrap.isPending) content = <Spin/>;
  else if (!bootstrap.data?.enabled)
    content = (
      <Result
        status="info"
        title={t('app.aiNew.disabled')}
        subTitle={t('app.aiNew.disabledDescription')}
      />
    );
  else if (!spaces.length)
    content = (
      <Result
        status="403"
        title={t('app.aiNew.noWorkspace')}
        subTitle={t('app.aiNew.noWorkspaceDescription')}
      />
    );
  else
    content = (
      <Space orientation="vertical" size="large" style={{width: '100%'}}>
        <Space wrap>
          <Typography.Text>{t('app.aiNew.workspace')}</Typography.Text>
          <Select
            aria-label={t('app.aiNew.workspace')}
            style={{minWidth: 260}}
            value={scope ? scopeValue(scope) : undefined}
            placeholder={t('app.aiNew.chooseWorkspace')}
            options={spaces.map((item) => ({
              value: scopeValue(item),
              label: `${item.tenantId} / ${item.workspaceId}`,
            }))}
            onChange={(value) => {
              const next = spaces.find((item) => scopeValue(item) === value);
              if (next) navigate(next);
            }}
          />
          <Button
            onClick={() => {
              void bootstrap.refetch();
            }}
          >
            {t('app.aiNew.refreshAccess')}
          </Button>
        </Space>
        {scope ? (
          <ConversationWorkspace
            key={`${userId}/${scopeValue(scope)}`}
            userId={userId}
            scope={scope}
            bootstrap={bootstrap.data}
            selectedId={params.get('conversationId') ?? ''}
          />
        ) : (
          <Alert type="warning" title={t('app.aiNew.invalidWorkspace')}/>
        )}
      </Space>
    );
  return (
    <PageContainer
      title={t('app.aiNew.pageTitle')}
      subTitle={t('app.aiNew.pageDescription')}
    >
      {content}
    </PageContainer>
  );
}
