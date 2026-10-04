import {Alert, Button, Checkbox, Input, Modal, Select, Space, Spin, Typography,} from 'antd';
import {useEffect, useRef, useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {getChatContext, getRetrievalArticles, getRetrievalPreview, previewChatRetrieval} from '@/services/ai-new/chat';
import type {RagContext, RetrievalMode} from '@/types/ai-new/chat';
import RagSources from './RagSources';
import {chatErrorText, isAccessError} from '@/features/ai-chat/errors';
import {useChat} from '@/features/ai-chat/hooks/useChat';
import {canRegenerateTurn, isTurnPending} from '@/features/ai-chat/executionState';
import type {ChatBootstrap, Conversation, WorkspaceSelection,} from '@/types/ai-new/conversation';
import {i18nText as t} from '@/utils/i18n';
import ChatMessageList from './ChatMessageList';

export default function ChatPanel({
                                    userId,
                                    scope,
                                    conversation,
                                    model,
                                    onLockChange,
                                    draft,
                                    onDraftChange,
                                  }: {
  userId: string;
  scope: WorkspaceSelection;
  conversation: Conversation;
  model: ChatBootstrap['defaultModel'];
  onLockChange: (id: string, locked: boolean) => void;
  draft: string;
  onDraftChange: (id: string, text: string, submittedText?: string) => void;
}) {
  const chat = useChat(userId, scope, conversation, model?.streaming ?? false);
  const accessDeniedRef = useRef(false);
  const setDraft = (text: string) => onDraftChange(conversation.conversationId, text);
  const [confirmation, setConfirmation] = useState<null | {
    text: string;
    replay: boolean;
    originalTurnId?: string;
    previewId?: string;
    context?: RagContext | null;
  }>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [retrievalMode, setRetrievalMode] = useState<RetrievalMode>('NONE');
  const [articleIds, setArticleIds] = useState<string[]>([]);
  const [semanticSearch, setSemanticSearch] = useState(true);
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<unknown>(null);
  const previewAttempt = useRef(0);
  const articleChoices = useQuery({
    queryKey: ['ai-new', userId, scope.tenantId, scope.workspaceId, 'rag-articles'],
    queryFn: ({signal}) => getRetrievalArticles(scope, signal),
    enabled: Boolean(model?.retrievalEnabled) && ['ARTICLE_FULL_TEXT', 'SELECTED_ARTICLES'].includes(retrievalMode),
    retry: false,
  });
  const transferIdentity = JSON.stringify([
    userId,
    model?.destination,
    model?.name,
    model?.bindingRef,
    model?.purpose,
    model?.retrievalEnabled,
    scope.tenantId, scope.workspaceId,
  ]);
  useEffect(() => {
    setConfirmation(null);
    setConfirmed(false);
    setRetrievalMode('NONE'); setArticleIds([]); setPreviewError(null);
    previewAttempt.current++;
    setPreviewing(false);
  }, [transferIdentity, conversation.conversationId]);
  useEffect(() => () => { previewAttempt.current++; }, []);
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
    previewing ||
    chat.busy ||
    !chat.history.isSuccess ||
    chat.history.isFetching ||
    Boolean(chat.pending) ||
    chat.unfinished;
  const locked = chat.busy || chat.unfinished || Boolean(chat.pending) || !chat.history.isSuccess;
  useEffect(() => {
    onLockChange(conversation.conversationId, locked);
  }, [onLockChange, conversation.conversationId, locked]);
  useEffect(() => () => {
    onLockChange(conversation.conversationId, false);
  }, [onLockChange, conversation.conversationId]);
  const latest = chat.turns.at(-1);
  const regeneratable = latest && canRegenerateTurn(latest) && !chat.unfinished;
  const openConfirmation = async (text: string, replay = false, originalTurnId?: string) => {
    const attempt = ++previewAttempt.current;
    setConfirmed(false); setPreviewError(null); setPreviewing(true);
    try {
      let context: RagContext | null = null;
      let previewId: string | undefined;
      if (replay && chat.pending?.body.previewId) {
        const preview = await getRetrievalPreview(scope, conversation.conversationId, chat.pending.body.previewId);
        if (preview.context.contentDigest !== chat.pending.body.expectedContextDigest) throw new Error('preview mismatch');
        context = preview.context; previewId = preview.previewId;
      } else if (originalTurnId || (replay && chat.pending?.kind === 'REGENERATION')) {
        const regenerationId = originalTurnId ?? (chat.pending?.kind === 'REGENERATION' ? chat.pending.body.originalTurnId : undefined);
        const original = chat.turns.find(turn => turn.turn.turnId === regenerationId);
        if (!original || original.execution?.resourceContext)
          context = await getChatContext(scope, conversation.conversationId, regenerationId!);
      } else if (!replay && retrievalMode !== 'NONE') {
        const preview = await previewChatRetrieval(scope, conversation.conversationId, conversation.version, text,
          {mode: retrievalMode, articleIds: retrievalMode === 'ARTICLE_LIBRARY' ? [] : articleIds, semanticSearch, maxResults: 10});
        context = preview.context; previewId = preview.previewId;
      }
      if (attempt === previewAttempt.current) setConfirmation({text, replay, originalTurnId, context, previewId});
    } catch (error) {
      if (attempt === previewAttempt.current) setPreviewError(error);
    } finally {
      if (attempt === previewAttempt.current) setPreviewing(false);
    }
  };
  const send = async () => {
    if (!confirmation || !confirmed || !sameBinding) return;
    const command = confirmation;
    setConfirmation(null);
    const newMessage = !command.replay && !command.originalTurnId;
    if (newMessage) onDraftChange(conversation.conversationId, '', command.text);
    const accepted = await chat.send(command.text, command.replay, command.originalTurnId, command.previewId && command.context ? {previewId: command.previewId, contentDigest: command.context.contentDigest} : undefined);
    if (!accepted && newMessage && !accessDeniedRef.current)
      onDraftChange(conversation.conversationId, command.text, '');
  };
  const accessDenied = isAccessError(chat.history.error) || isAccessError(chat.commandError) || isAccessError(chat.cancelError);
  useEffect(() => {
    accessDeniedRef.current = accessDenied;
    if (accessDenied) onDraftChange(conversation.conversationId, '');
  }, [accessDenied, onDraftChange, conversation.conversationId]);
  if (accessDenied)
    return (
      <Alert
        type="error"
        title={t('app.aiNew.error.access')}
        action={
          <Space>
            <Button onClick={() => { void chat.refresh(); }}>{t('app.aiNew.refresh')}</Button>
            {latest?.execution && isTurnPending(latest) && <Button danger loading={chat.cancelling}
                onClick={() => { void chat.cancel(latest.turn.turnId); }}>{t('app.aiNew.stopGeneration')}</Button>}
          </Space>
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
          disabled={chat.busy || chat.history.isFetching}
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
        {latest?.execution && isTurnPending(latest) && <Button danger aria-label={t('app.aiNew.stopGeneration')}
                                                               loading={chat.cancelling}
                                                               disabled={chat.busy || chat.history.isError || Boolean(chat.cancellation && chat.cancellation.turnId === latest.turn.turnId && chat.cancellation.status !== 'UNCONFIRMED')}
                                                               onClick={() => {
                                                                 void chat.cancel(latest.turn.turnId);
                                                               }}>{t('app.aiNew.stopGeneration')}</Button>}
        {regeneratable && <Button disabled={blocked}
                                  onClick={() => openConfirmation(latest.turn.input.flatMap((message) => message.parts.map((part) => part.text)).join('\n'), false, latest.turn.turnId)}>{t('app.aiNew.regenerateAnswer')}</Button>}
      </Space>
      {chat.history.error ? (
        <Alert type="error" title={chatErrorText(chat.history.error)}/>
      ) : chat.history.isPending ? (
        <Spin/>
      ) : (
        <ChatMessageList turns={chat.turns} submission={chat.sending ? chat.pending : null}/>
      )}
      {Boolean(chat.commandError) && (
        <Alert type="error" title={chatErrorText(chat.commandError)}/>
      )}
      {chat.cancellation?.turnId === latest?.turn.turnId && chat.cancellation &&
        <Alert type={chat.cancellation.status === 'UNCONFIRMED' ? 'warning' : 'info'}
               title={t(`app.aiNew.cancellation.${chat.cancellation.status}`)}
               description={t('app.aiNew.cancellationDescription')}/>}
      {Boolean(chat.cancelError) && <Alert type="error" title={chatErrorText(chat.cancelError)}/>}
      {chat.pending && !chat.sending && (
        <Alert
          type="warning"
          title={t('app.aiNew.pendingSubmission')}
          description={t('app.aiNew.pendingDescription')}
          action={
            <Button
              disabled={chat.busy ||
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
      {latest && !latest.execution && latest.turn.status !== 'REJECTED' && !chat.pending &&
        <Alert type="warning" title={t('app.aiNew.unlinkedSubmission')}/>}
      {!sameBinding && (
        <Alert type="warning" title={t('app.aiNew.bindingChanged')}/>
      )}
      {chat.storageFailed && (
        <Alert type="error" title={t('app.aiNew.storageFailed')}/>
      )}
      {model?.retrievalEnabled && <Space orientation="vertical" style={{width: '100%'}}>
        <Typography.Text strong>{t('app.aiNew.rag.mode')}</Typography.Text>
        <Select aria-label={t('app.aiNew.rag.mode')} value={retrievalMode} disabled={blocked}
                style={{width: '100%'}} onChange={(mode: RetrievalMode) => {setRetrievalMode(mode); setArticleIds([]); setPreviewError(null);}}
                options={(['NONE', 'ARTICLE_FULL_TEXT', 'SELECTED_ARTICLES', 'ARTICLE_LIBRARY'] as const)
                  .map(mode => ({value: mode, label: t(`app.aiNew.rag.mode.${mode}`)}))}/>
        {['ARTICLE_FULL_TEXT', 'SELECTED_ARTICLES'].includes(retrievalMode) && <>
          <Select mode="multiple" aria-label={t('app.aiNew.rag.articles')} placeholder={t('app.aiNew.rag.articles')}
                  value={articleIds} disabled={blocked} loading={articleChoices.isFetching} style={{width: '100%'}}
                  maxCount={retrievalMode === 'ARTICLE_FULL_TEXT' ? 1 : 16} optionFilterProp="label"
                  onChange={setArticleIds} options={articleChoices.data?.map(article => ({value: article.id, label: `${article.title} (#${article.id})`}))}/>
          {articleChoices.error && <Alert type="error" title={chatErrorText(articleChoices.error)}/>}
        </>}
        {['SELECTED_ARTICLES', 'ARTICLE_LIBRARY'].includes(retrievalMode) && <Checkbox checked={semanticSearch} disabled={blocked}
            onChange={event => setSemanticSearch(event.target.checked)}>{t('app.aiNew.rag.semantic')}</Checkbox>}
        {retrievalMode !== 'NONE' && <Typography.Text type="secondary">{t('app.aiNew.rag.hint')}</Typography.Text>}
      </Space>}
      {Boolean(previewError) && <Alert type="error" title={chatErrorText(previewError)}/>}
      <label htmlFor="ai-new-message">
        <Typography.Text strong>{t('app.aiNew.messageInput')}</Typography.Text>
      </label>
      <Input.TextArea
        id="ai-new-message"
        aria-describedby="ai-new-composer-hint ai-new-message-bytes"
        status={bytes > (model?.contextMaxBytes ?? 0) ? 'error' : undefined}
        value={draft}
        disabled={blocked}
        autoSize={{minRows: 3, maxRows: 10}}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={t('app.aiNew.messagePlaceholder')}
        onKeyDown={(event) => {
          if (
            (event.ctrlKey || event.metaKey) &&
            event.key === 'Enter' &&
            !event.nativeEvent.isComposing &&
            !blocked &&
            draft.trim() &&
            bytes <= (model?.contextMaxBytes ?? 0) &&
            (!['ARTICLE_FULL_TEXT', 'SELECTED_ARTICLES'].includes(retrievalMode) || articleIds.length > 0)
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
          loading={chat.sending || previewing}
          disabled={
            blocked || !draft.trim() || bytes > (model?.contextMaxBytes ?? 0)
            || (['ARTICLE_FULL_TEXT', 'SELECTED_ARTICLES'].includes(retrievalMode) && articleIds.length === 0)
          }
          onClick={() => openConfirmation(draft)}
        >
          {t('app.aiNew.send')}
        </Button>
        <Typography.Text
          id="ai-new-message-bytes"
          type={bytes > (model?.contextMaxBytes ?? 0) ? 'danger' : 'secondary'}
        >
          {t('app.aiNew.messageBytes', {
            bytes,
            limit: model?.contextMaxBytes ?? 0,
          })}
        </Typography.Text>
      </Space>
      <Typography.Text id="ai-new-composer-hint" type="secondary">
        {t('app.aiNew.composerHint')}
      </Typography.Text>
      <Modal
        open={confirmation !== null}
        title={t(confirmation?.originalTurnId || (confirmation?.replay && chat.pending?.kind === 'REGENERATION') ? 'app.aiNew.regenerateTitle' : 'app.aiNew.transferTitle')}
        okText={t('app.aiNew.confirmSend')}
        cancelText={t('app.aiNew.cancel')}
        okButtonProps={{disabled: !confirmed || chat.busy || !sameBinding}}
        onOk={() => {
          void send();
        }}
        onCancel={() => setConfirmation(null)}
      >
        <Typography.Paragraph>
          {t(confirmation?.originalTurnId || (confirmation?.replay && chat.pending?.kind === 'REGENERATION') ? 'app.aiNew.regenerateDescription' : 'app.aiNew.transferDescription')}
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
        {confirmation?.context && <>
          <Typography.Paragraph>{t('app.aiNew.rag.budget', {bytes: confirmation.context.budget.usedInputBytes,
            limit: confirmation.context.budget.inputByteLimit, tokens: confirmation.context.budget.estimatedInputTokens})}</Typography.Paragraph>
          <RagSources fragments={confirmation.context.fragments}/>
          <details><summary>{t('app.aiNew.rag.inputs')}</summary>
            <div style={{whiteSpace: 'pre-wrap', maxHeight: 300, overflow: 'auto'}}>{confirmation.context.messages
              .map(message => message.parts.map(part => part.text).join('\n')).join('\n\n')}</div>
          </details>
        </>}
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
