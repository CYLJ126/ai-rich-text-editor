import {Alert, Button, Empty, Input, Pagination, Tag} from 'antd';
import React, {useMemo} from 'react';
import {AiApiError, type ChatMessage, type ConversationResponse,} from '@/services/arte-ai';
import type {ChatTestConfig} from './config';
import {hasAvailableBudget, HISTORY_PAGE_SIZE, useChatSession,} from './useChatSession';

function messageText(message: ChatMessage) {
  return message.content
    .map((part) => ('text' in part ? part.text : `[${part.modality}]`))
    .join('\n');
}

export default function ChatPanel({
                                    config,
                                    conversation,
                                    dirty,
                                    onUpdated,
                                    t,
                                    maxInputBytes,
                                  }: {
  config: ChatTestConfig;
  conversation: ConversationResponse;
  dirty: boolean;
  onUpdated: (conversation: ConversationResponse) => void;
  t: (key: string) => string;
  maxInputBytes?: number;
}) {
  const chat = useChatSession(config, conversation, onUpdated, maxInputBytes);
  const inputTooLarge = useMemo(() => maxInputBytes !== undefined
    && new TextEncoder().encode(chat.draft.trim()).length > maxInputBytes, [chat.draft, maxInputBytes]);
  const available = chat.budget && hasAvailableBudget(chat.budget.available);
  const blocked =
    dirty ||
    conversation.state !== 'ACTIVE' ||
    chat.historyLoading ||
    !!chat.historyError ||
    chat.submitting ||
    chat.activeIds.length > 0 ||
    chat.unresolved ||
    !available;
  const error = (value: unknown, retry?: () => void) =>
    value ? (
      <Alert
        type="error"
        showIcon
        title={
          value instanceof AiApiError
            ? `${value.message} (HTTP ${value.httpStatus}${value.code ? ` / ${value.code}` : ''})`
            : t('error.network')
        }
        description={
          value instanceof AiApiError
            ? value.httpStatus === 401
              ? t('error.login')
              : value.httpStatus === 403
                ? t('error.permission')
                : undefined
            : undefined
        }
        action={
          retry && (
            <Button size="small" disabled={dirty} onClick={retry}>
              {t('retry')}
            </Button>
          )
        }
      />
    ) : null;
  const current = chat.invocationId
    ? chat.invocations[chat.invocationId]
    : undefined;
  const state =
    current?.status?.state ?? (chat.invocationId ? 'ACCEPTED' : null);
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="small"
          loading={chat.historyLoading}
          disabled={dirty || chat.submitting || !!chat.pending}
          onClick={() => void chat.loadHistory()}
        >
          {t('refreshHistory')}
        </Button>
        <Button
          size="small"
          loading={chat.budgetLoading}
          disabled={dirty}
          onClick={() => void chat.loadBudget()}
        >
          {t('refreshBudget')}
        </Button>
      </div>
      {error(chat.historyError, () => void chat.loadHistory())}
      {chat.historyLoading && <p role="status">{t('loadingHistory')}</p>}
      {!chat.historyLoading && !chat.historyError && !chat.turns.length && (
        <Empty description={t('historyEmpty')}/>
      )}
      <ol
        className="m-0 max-h-[480px] list-none space-y-4 overflow-y-auto p-0"
        aria-label={t('history')}
      >
        {chat.turns.map((turn) => {
          const id = turn.selectedInvocationId ?? turn.invocationIds.at(-1);
          const invocation = id ? chat.invocations[id] : undefined;
          const result = invocation?.result;
          const model =
            result?.kind === 'GENERATION' ? result.result.value : null;
          return (
            <li
              key={turn.turnId}
              className="space-y-2 rounded border border-solid border-[var(--ant-color-border)] p-3"
            >
              <div>
                <Tag>{t('role.USER')}</Tag>
                <span className="text-xs">#{turn.sequence}</span>
              </div>
              <p className="m-0 whitespace-pre-wrap break-words">
                {messageText(turn.userMessage)}
              </p>
              <div>
                <Tag color="blue">{t('role.ASSISTANT')}</Tag>
                {invocation?.status && (
                  <Tag>{t(`execution.${invocation.status.state}`)}</Tag>
                )}
                {model && !model.complete && (
                  <Tag color="orange">{t('partialResult')}</Tag>
                )}
              </div>
              {model ? (
                <>
                  {model.outputs.map((message) => (
                    <p
                      key={message.messageId}
                      className="m-0 whitespace-pre-wrap break-words"
                    >
                      {messageText(message)}
                    </p>
                  ))}
                  <p className="m-0 text-xs text-[var(--ant-color-text-secondary)]">
                    {t('usage')}：{model.usage.inputTokens ?? t('unknown')} /{' '}
                    {model.usage.outputTokens ?? t('unknown')}
                    {' · '}
                    {t('finishReason')}：{model.finishReason}
                  </p>
                </>
              ) : invocation?.streamText ? (
                <>
                  <section
                    className="m-0 whitespace-pre-wrap break-words"
                    aria-label={t('streamedReply')}
                  >
                    {invocation.streamText}
                  </section>
                  <p
                    role="status"
                    className="m-0 text-xs text-[var(--ant-color-text-secondary)]"
                  >
                    {t(
                      invocation.status &&
                      !['ACCEPTED', 'QUEUED', 'RUNNING'].includes(
                        invocation.status.state,
                      )
                        ? 'streamedPartial'
                        : 'streamingReply',
                    )}
                  </p>
                </>
              ) : (
                <p className="m-0 text-sm text-[var(--ant-color-text-secondary)]">
                  {t(
                    invocation?.status &&
                    ['ACCEPTED', 'QUEUED', 'RUNNING'].includes(
                      invocation.status.state,
                    )
                      ? 'waitingReply'
                      : 'noResult',
                  )}
                </p>
              )}
              {invocation?.status?.error && (
                <Alert
                  type="warning"
                  title={
                    model?.finishReason === 'LENGTH'
                      ? t('outputLimitReached')
                      : invocation.status.error.code
                  }
                  description={
                    <>
                      {model?.finishReason === 'LENGTH' && (
                        <p>{t('outputLimitHint')}</p>
                      )}
                      {invocation.status.error.code} · {t('error.phase')}：
                      {invocation.status.error.phase} · {t('correlationId')}：
                      {invocation.status.error.correlationId}
                    </>
                  }
                />
              )}
              {error(invocation?.error, () => void chat.loadHistory(chat.page))}
              {id && (
                <p className="m-0 break-all text-xs text-[var(--ant-color-text-secondary)]">
                  {t('invocationId')}：{id}
                </p>
              )}
            </li>
          );
        })}
      </ol>
      {current?.streamText &&
        !current.result &&
        !chat.turns.some(
          (turn) =>
            (turn.selectedInvocationId ?? turn.invocationIds.at(-1)) ===
            chat.invocationId,
        ) && (
          <div className="space-y-2 rounded border border-solid border-[var(--ant-color-border)] p-3">
            <span>{t('role.ASSISTANT')}</span>
            <section
              className="m-0 whitespace-pre-wrap break-words"
              aria-label={t('streamedReply')}
            >
              {current.streamText}
            </section>
            <p role="status" className="m-0 text-xs">
              {t(
                current.status &&
                !['ACCEPTED', 'QUEUED', 'RUNNING'].includes(
                  current.status.state,
                )
                  ? 'streamedPartial'
                  : 'streamingReply',
              )}
            </p>
          </div>
        )}
      <Pagination
        size="small"
        simple
        current={chat.page}
        total={chat.total}
        pageSize={HISTORY_PAGE_SIZE}
        hideOnSinglePage
        showSizeChanger={false}
        disabled={
          dirty || chat.historyLoading || chat.submitting || !!chat.pending
        }
        onChange={(page) => void chat.loadHistory(page)}
      />
      <div className="flex flex-wrap gap-2" aria-live="polite">
        <span>
          {t('invocationState')}：{state ? t(`execution.${state}`) : '—'}
        </span>
        <span>
          {t('budgetAvailable')}：
          {chat.budget
            ? `${chat.budget.available} ${chat.budget.currency}`
            : t(chat.budgetLoading ? 'loadingBudget' : 'notQueried')}
        </span>
        {chat.invocationId &&
          chat.invocations[chat.invocationId]?.status?.budgetState && (
            <span>
              {t('invocationBudgetState')}：
              {t(
                `budgetState.${chat.invocations[chat.invocationId].status?.budgetState}`,
              )}
            </span>
          )}
      </div>
      {chat.budget && (
        <p className="m-0 text-xs text-[var(--ant-color-text-secondary)]">
          {t('budgetLimit')}：{chat.budget.limit} · {t('budgetHeld')}：
          {chat.budget.held} · {t('budgetCharged')}：{chat.budget.charged}
        </p>
      )}
      {error(chat.budgetError, () => void chat.loadBudget())}
      {chat.budget && !available && (
        <Alert type="warning" title={t('insufficientBudget')}/>
      )}
      {chat.unresolved && <Alert type="warning" title={t('unknownOutcome')}/>}
      {error(chat.pollError, chat.resume)}
      {chat.pollPaused && (
        <Alert
          type="warning"
          title={t('pollPaused')}
          action={
            <Button size="small" disabled={dirty} onClick={chat.resume}>
              {t('resumePolling')}
            </Button>
          }
        />
      )}
      {chat.watching && (
        <p role="status" className="m-0 text-xs">
          {t('polling')}
        </p>
      )}
      {error(chat.submitError)}
      {chat.pending && <Alert type="warning" title={t('retryMessageHint')}/>}
      {dirty && <p className="m-0 text-xs">{t('applyDraft')}</p>}
      {!chat.pending && inputTooLarge && <Alert type="warning" title={t('inputTooLarge')}/>}
      <Input.TextArea
        value={chat.draft}
        onChange={(event) => chat.setDraft(event.target.value)}
        disabled={blocked || !!chat.pending}
        maxLength={1_000_000}
        aria-label={t('messageInput')}
        autoSize={{minRows: 3, maxRows: 8}}
        placeholder={t('messagePlaceholder')}
      />
      <div className="flex justify-end">
        <Button
          type="primary"
          loading={chat.submitting}
          disabled={
            chat.pending
              ? dirty || chat.submitting
              : blocked || inputTooLarge || !chat.draft.trim()
          }
          onClick={() => void chat.send()}
        >
          {t(chat.pending ? 'retryMessage' : 'send')}
        </Button>
      </div>
    </div>
  );
}
