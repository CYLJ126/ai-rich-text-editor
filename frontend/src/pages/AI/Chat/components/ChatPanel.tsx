import {Alert, Button, Checkbox, Input, Modal, Space, Spin, Typography,} from 'antd';
import {useEffect, useState} from 'react';
import {chatErrorText, isAccessError} from '@/features/ai-chat/errors';
import {useChat} from '@/features/ai-chat/hooks/useChat';
import type {ChatBootstrap, Conversation, WorkspaceSelection,} from '@/types/ai-new/conversation';
import {i18nText as t} from '@/utils/i18n';
import ChatMessageList from './ChatMessageList';

export default function ChatPanel({
                                    userId,
                                    scope,
                                    conversation,
                                    model,
                                  }: {
  userId: string;
  scope: WorkspaceSelection;
  conversation: Conversation;
  model: ChatBootstrap['defaultModel'];
}) {
  const chat = useChat(userId, scope, conversation);
  const [draft, setDraft] = useState('');
  const [confirmation, setConfirmation] = useState<null | {
    text: string;
    replay: boolean;
  }>(null);
  const [confirmed, setConfirmed] = useState(false);
  const transferIdentity = JSON.stringify([
    model?.destination,
    model?.name,
    model?.bindingRef,
    model?.purpose,
  ]);
  useEffect(() => {
    setConfirmation(null);
    setConfirmed(false);
  }, [transferIdentity]);
  const bytes = new TextEncoder().encode(draft).length;
  const sameBinding =
    model &&
    conversation.modelBindingRef.definitionType ===
    model.bindingRef.definitionType &&
    conversation.modelBindingRef.definitionId ===
    model.bindingRef.definitionId &&
    conversation.modelBindingRef.version === model.bindingRef.version;
  const blocked =
    !sameBinding ||
    chat.sending ||
    !chat.history.isSuccess ||
    chat.history.isFetching ||
    Boolean(chat.pending) ||
    chat.unfinished;
  const openConfirmation = (text: string, replay = false) => {
    setConfirmed(false);
    setConfirmation({text, replay});
  };
  const send = async () => {
    if (!confirmation || !confirmed || !sameBinding) return;
    const command = confirmation;
    setConfirmation(null);
    const accepted = await chat.send(command.text, command.replay);
    if (accepted && !command.replay) setDraft('');
  };
  if (isAccessError(chat.history.error) || isAccessError(chat.commandError))
    return (
      <Alert
        type="error"
        title={t('app.aiNew.error.access')}
        action={
          <Button
            onClick={() => {
              void chat.refresh();
            }}
          >
            {t('app.aiNew.refresh')}
          </Button>
        }
      />
    );
  return (
    <Space
      orientation="vertical"
      size="middle"
      style={{width: '100%', marginTop: 24}}
    >
      <Space wrap>
        <Typography.Title level={4} style={{margin: 0}}>
          {t('app.aiNew.messages')}
        </Typography.Title>
        <Button
          disabled={chat.sending || chat.history.isFetching}
          onClick={() => {
            void chat.refresh();
          }}
        >
          {t('app.aiNew.refresh')}
        </Button>
        {chat.history.hasNextPage && (
          <Button
            loading={chat.history.isFetchingNextPage}
            onClick={() => {
              void chat.history.fetchNextPage();
            }}
          >
            {t('app.aiNew.loadOlder')}
          </Button>
        )}
      </Space>
      {chat.history.error ? (
        <Alert type="error" title={chatErrorText(chat.history.error)}/>
      ) : chat.history.isPending ? (
        <Spin/>
      ) : (
        <ChatMessageList turns={chat.turns}/>
      )}
      {Boolean(chat.commandError) && !chat.pending && (
        <Alert type="error" title={chatErrorText(chat.commandError)}/>
      )}
      {chat.pending && !chat.sending && (
        <Alert
          type="warning"
          title={t('app.aiNew.pendingSubmission')}
          description={t('app.aiNew.pendingDescription')}
          action={
            <Button
              disabled={
                chat.history.isFetching ||
                !chat.history.isSuccess ||
                !sameBinding
              }
              onClick={() =>
                openConfirmation(chat.pending?.body.text ?? '', true)
              }
            >
              {t('app.aiNew.replaySubmission')}
            </Button>
          }
        />
      )}
      {chat.observationPaused && (
        <Alert type="info" title={t('app.aiNew.observationPaused')}/>
      )}
      {!sameBinding && (
        <Alert type="warning" title={t('app.aiNew.bindingChanged')}/>
      )}
      {chat.storageFailed && (
        <Alert type="error" title={t('app.aiNew.storageFailed')}/>
      )}
      <label htmlFor="ai-new-message">
        <Typography.Text strong>{t('app.aiNew.messageInput')}</Typography.Text>
      </label>
      <Input.TextArea
        id="ai-new-message"
        value={draft}
        disabled={blocked}
        autoSize={{minRows: 3, maxRows: 10}}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={t('app.aiNew.messagePlaceholder')}
        onKeyDown={(event) => {
          if (
            event.ctrlKey &&
            event.key === 'Enter' &&
            !event.nativeEvent.isComposing &&
            !blocked &&
            draft.trim() &&
            bytes <= (model?.contextMaxBytes ?? 0)
          ) {
            event.preventDefault();
            openConfirmation(draft);
          }
        }}
      />
      <Space wrap>
        <Button
          type="primary"
          aria-label={t('app.aiNew.send')}
          loading={chat.sending}
          disabled={
            blocked || !draft.trim() || bytes > (model?.contextMaxBytes ?? 0)
          }
          onClick={() => openConfirmation(draft)}
        >
          {t('app.aiNew.send')}
        </Button>
        <Typography.Text
          type={bytes > (model?.contextMaxBytes ?? 0) ? 'danger' : 'secondary'}
        >
          {t('app.aiNew.messageBytes', {
            bytes,
            limit: model?.contextMaxBytes ?? 0,
          })}
        </Typography.Text>
      </Space>
      <Modal
        open={confirmation !== null}
        title={t('app.aiNew.transferTitle')}
        okText={t('app.aiNew.confirmSend')}
        cancelText={t('app.aiNew.cancel')}
        okButtonProps={{disabled: !confirmed || chat.sending || !sameBinding}}
        onOk={() => {
          void send();
        }}
        onCancel={() => setConfirmation(null)}
      >
        <Typography.Paragraph>
          {t('app.aiNew.transferDescription')}
        </Typography.Paragraph>
        <Typography.Paragraph>
          <Typography.Text strong>
            {t('app.aiNew.destination')}：
          </Typography.Text>
          <span style={{overflowWrap: 'anywhere'}}>{model?.destination}</span>
        </Typography.Paragraph>
        <Typography.Paragraph>
          {t('app.aiNew.purpose')}：{t('app.aiNew.textGeneration')}
        </Typography.Paragraph>
        <Typography.Paragraph
          style={{whiteSpace: 'pre-wrap', maxHeight: 160, overflow: 'auto'}}
        >
          {confirmation?.text}
        </Typography.Paragraph>
        <Checkbox
          checked={confirmed}
          onChange={(event) => setConfirmed(event.target.checked)}
        >
          {t('app.aiNew.transferConsent')}
        </Checkbox>
      </Modal>
    </Space>
  );
}
