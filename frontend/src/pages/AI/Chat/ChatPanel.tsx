import {Alert, Button, Empty, Input, Pagination, Select, Tag} from 'antd';
import React, {useMemo, useState} from 'react';
import {AiApiError, type ChatMessage, type ConversationResponse,} from '@/services/arte-ai';
import ReconciliationPanel from './ReconciliationPanel';
import type {ChatTestConfig} from './config';
import type {ConfigurationRejected} from './configurationInvalidation';
import {hasAvailableBudget, HISTORY_PAGE_SIZE, isUserStoppedGeneration, useChatSession,} from './useChatSession';

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
                                    onConfigurationRejected,
                                  }: {
  config: ChatTestConfig;
  conversation: ConversationResponse;
  dirty: boolean;
  onUpdated: (conversation: ConversationResponse) => void;
  t: (key: string) => string;
  maxInputBytes?: number;
  onConfigurationRejected?: ConfigurationRejected;
}) {
  const chat = useChatSession(config, conversation, onUpdated, maxInputBytes, onConfigurationRejected);
  const [viewedCandidates, setViewedCandidates] = useState<Record<string, string>>({});
  const inputTooLarge = useMemo(() => maxInputBytes !== undefined
    && new TextEncoder().encode(chat.draft.trim()).length > maxInputBytes, [chat.draft, maxInputBytes]);
  const available = chat.budget && hasAvailableBudget(chat.budget.available);
  const blocked =
    dirty ||
    conversation.state !== 'ACTIVE' ||
    chat.historyLoading ||
    !!chat.historyError ||
    chat.submitting ||
    !!chat.pendingRegeneration ||
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
          disabled={dirty || chat.submitting || !!chat.pending || !!chat.pendingRegeneration}
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
        <ReconciliationPanel config={config} t={t} onConfirmed={chat.refreshAfterReconciliation}/>
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
          const chosen = viewedCandidates[turn.turnId];
          const id = chosen && turn.invocationIds.includes(chosen) ? chosen : turn.invocationIds.at(-1);
          const latestId = turn.invocationIds.at(-1);
          const latestStatus = latestId ? chat.invocations[latestId]?.status : undefined;
          const latestTurn = chat.page === Math.max(1, Math.ceil(chat.total / HISTORY_PAGE_SIZE)) && turn.sequence === chat.total;
          const regeneratable = latestStatus && !['ACCEPTED', 'QUEUED', 'RUNNING'].includes(latestStatus.state) &&
            (latestStatus.state !== 'UNKNOWN' || isUserStoppedGeneration(latestStatus));
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
              {turn.invocationIds.length > 1 && <div className="flex flex-wrap gap-2">
                <span>{t('viewReply')}：</span>
                <Select aria-label={`${t('viewReply')} #${turn.sequence}`} value={id} style={{minWidth: 160}}
                        options={turn.invocationIds.map((candidate, index) => ({
                          value: candidate,
                          label: `${t('replyCandidate')} ${index + 1}${candidate === turn.selectedInvocationId ? ` · ${t('usedInContext')}` : ''}`
                        }))}
                        onChange={candidate => {
                          setViewedCandidates(previous => ({...previous, [turn.turnId]: candidate}));
                          void chat.inspectCandidate(candidate);
                        }}/>
              </div>}
              <div>
                <Tag color="blue">{t('role.ASSISTANT')}</Tag>
                {invocation?.status && (
                  <Tag>{t(isUserStoppedGeneration(invocation.status) ? 'stoppedGeneration' : `execution.${invocation.status.state}`)}</Tag>
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
              {latestTurn && latestId && regeneratable && <Button size="small"
                                                                  disabled={blocked || !!chat.pending || !!chat.pendingRegeneration}
                                                                  title={t('regenerateHint')}
                                                                  onClick={() => {
                                                                    setViewedCandidates(previous => {
                                                                      const next = {...previous};
                                                                      delete next[turn.turnId];
                                                                      return next;
                                                                    });
                                                                    void chat.regenerate(latestId);
                                                                  }}>
                {t('regenerate')}
              </Button>}
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
            !!chat.invocationId && turn.invocationIds.includes(chat.invocationId),
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
          dirty || chat.historyLoading || chat.submitting || !!chat.pending || !!chat.pendingRegeneration
        }
        onChange={(page) => void chat.loadHistory(page)}
      />
      <div className="flex flex-wrap gap-2" aria-live="polite">
        <span>
          {t('invocationState')}：{state ? t(isUserStoppedGeneration(current?.status) ? 'stoppedGeneration' : `execution.${state}`) : '—'}
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
      {error(chat.stopError, chat.retryStop)}
      {isUserStoppedGeneration(current?.status) && (
        <Alert type="info" title={t('stoppedPendingReconciliation')}/>
      )}
      {chat.stopRequested === chat.invocationId && state && ['ACCEPTED', 'QUEUED', 'RUNNING'].includes(state) && (
        <p role="status">{t('stopRequested')}</p>
      )}
      {error(chat.submitError)}
      {chat.pendingRegeneration && <Alert type="warning" title={t('retryRegenerationHint')}/>}
      {chat.pending && <Alert type="warning" title={t('retryMessageHint')}/>}
      {dirty && <p className="m-0 text-xs">{t('applyDraft')}</p>}
      {!chat.pending && inputTooLarge && <Alert type="warning" title={t('inputTooLarge')}/>}
      <Input.TextArea
        value={chat.draft}
        onChange={(event) => chat.setDraft(event.target.value)}
        disabled={blocked || !!chat.pending || !!chat.pendingRegeneration}
        maxLength={1_000_000}
        aria-label={t('messageInput')}
        autoSize={{minRows: 3, maxRows: 8}}
        placeholder={t('messagePlaceholder')}
      />
      <div className="flex justify-end gap-2">
        {chat.pendingRegeneration && <Button loading={chat.submitting} disabled={dirty || chat.submitting}
                                             onClick={() => void chat.regenerate()}>{t('retryRegeneration')}</Button>}
        {chat.invocationId && state && ['ACCEPTED', 'QUEUED', 'RUNNING'].includes(state) && (
          <Button
            loading={chat.stopping}
            aria-label={t(chat.stopRequested === chat.invocationId ? 'stopping' : 'stopGeneration')}
            disabled={chat.stopping || chat.stopRequested === chat.invocationId}
            onClick={() => {
              if (chat.invocationId) void chat.stop(chat.invocationId);
            }}
          >
            {t(chat.stopRequested === chat.invocationId ? 'stopping' : 'stopGeneration')}
          </Button>
        )}
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
