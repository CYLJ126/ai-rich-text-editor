import {Alert, Button, Empty, Space, Tag, Typography} from 'antd';
import {useLayoutEffect, useRef, useState} from 'react';
import AssistantMarkdown from './AssistantMarkdown';
import CopyTextButton from './CopyTextButton';
import {isTurnPending} from '@/features/ai-chat/executionState';
import {executionErrorText} from '@/features/ai-chat/errors';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import {i18nText as t} from '@/utils/i18n';

export default function ChatMessageList({
                                          turns,
                                        }: {
  turns: ChatTurnResult[];
}) {
  const viewport = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const previous = useRef<{ first: string; last: string; height: number } | null>(null);
  const [hasUpdate, setHasUpdate] = useState(false);
  const first = turns[0]?.turn.turnId ?? '';
  const last = JSON.stringify(turns.at(-1));
  useLayoutEffect(() => {
    const element = viewport.current;
    if (!element) return;
    const old = previous.current;
    if (!old) element.scrollTop = element.scrollHeight;
    else if (old.first !== first && old.last === last) {
      // Prepending history keeps the same content under the reader's eyes.
      element.scrollTop += element.scrollHeight - old.height;
    } else if (old.last !== last) {
      if (following.current) element.scrollTop = element.scrollHeight;
      else setHasUpdate(true);
    }
    previous.current = {first, last, height: element.scrollHeight};
  }, [first, last]);
  return (
    <div style={{minWidth: 0}}>
      {/* biome-ignore lint/a11y/noNoninteractiveTabindex: The scrollable history needs keyboard scrolling, including in Safari. */}
      <div ref={viewport} role="log" tabIndex={0} aria-label={t('app.aiNew.messages')} aria-live="polite"
           style={{
             maxHeight: 'clamp(260px, 55vh, 640px)',
             minHeight: 160,
             overflowY: 'auto',
             overflowX: 'hidden',
             paddingInline: 4,
             overflowAnchor: 'none'
           }}
           onScroll={(event) => {
             const element = event.currentTarget;
             following.current = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
             if (following.current) setHasUpdate(false);
           }}>

      {!turns.length && <Empty description={t('app.aiNew.noMessages')}/>}
      {turns.map((item) => {
        const state = item.execution?.status ?? item.turn.status;
        const failure = item.turn.rejectionError ?? item.execution?.error;
        const input = item.turn.input.flatMap((message) => message.parts.map((part) => part.text)).join('\n');
        const output = item.execution?.result?.output.map((part) => part.text).join('\n') ?? '';
        return (
          <article
            key={item.turn.turnId}
            style={{
              borderBottom: '1px solid var(--ant-color-border-secondary)',
              padding: '16px 0',
            }}
          >
            <Space wrap>
              <Typography.Text strong>
                {t('app.aiNew.userMessage')}
              </Typography.Text>
              {item.turn.kind === 'REGENERATION' && (
                <Tag>{t('app.aiNew.regeneration')}</Tag>
              )}
              <Typography.Text type="secondary">
                {new Date(item.turn.createdAt).toLocaleString()}
              </Typography.Text>
              <CopyTextButton text={input} label={t('app.aiNew.copyQuestion')}/>
            </Space>
            <div
              style={{
                whiteSpace: 'pre-wrap',
                overflowWrap: 'anywhere',
                margin: '8px 0 16px',
              }}
            >
              {input}
            </div>
            <Space wrap>
              <Typography.Text strong>
                {t('app.aiNew.assistantMessage')}
              </Typography.Text>
              <Tag
                color={
                  state === 'SUCCEEDED'
                    ? 'green'
                    : isTurnPending(item)
                      ? 'processing'
                      : 'default'
                }
              >
                {t(`app.aiNew.turn.${state}`)}
              </Tag>
              {state === 'SUCCEEDED' && <CopyTextButton text={output} label={t('app.aiNew.copyAnswer')}/>}
            </Space>
            {item.execution?.status === 'SUCCEEDED' && <AssistantMarkdown text={output}/>}
            {item.execution?.status !== 'SUCCEEDED' && item.execution?.partialText && (
              <div style={{marginTop: 8}}>
                <AssistantMarkdown text={item.execution.partialText}/>
                <Typography.Text type="secondary">{t('app.aiNew.partialAnswer')}</Typography.Text>
              </div>
            )}
            {state === 'OUTCOME_UNKNOWN' && (
              <Alert
                type="warning"
                title={t('app.aiNew.outcomeUnknown')}
                style={{marginTop: 8}}
              />
            )}
            {failure && (
              <Typography.Paragraph type="secondary" style={{marginTop: 8}}>
                {executionErrorText(failure)}
              </Typography.Paragraph>
            )}
            {state !== 'SUCCEEDED' && !['ACCEPTED', 'RUNNING'].includes(state) &&
              <Typography.Paragraph type="secondary">
                {t('app.aiNew.referenceId')}：{item.execution?.executionId ?? item.turn.turnId}
              </Typography.Paragraph>}
          </article>
        );
      })}
      </div>
      {hasUpdate && <Button style={{marginTop: 8}} onClick={() => {
        const element = viewport.current;
        if (element) element.scrollTop = element.scrollHeight;
        following.current = true;
        setHasUpdate(false);
      }}>{t('app.aiNew.latestMessages')}</Button>}
    </div>
  );
}
