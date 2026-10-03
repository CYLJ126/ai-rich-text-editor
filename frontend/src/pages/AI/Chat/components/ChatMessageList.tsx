import {Alert, Empty, Space, Tag, Typography} from 'antd';
import {isTurnPending} from '@/features/ai-chat/executionState';
import {executionErrorText} from '@/features/ai-chat/errors';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import {i18nText as t} from '@/utils/i18n';

export default function ChatMessageList({
                                          turns,
                                        }: {
  turns: ChatTurnResult[];
}) {
  return (
    <div role="log" aria-label={t('app.aiNew.messages')} aria-live="polite">
      {!turns.length && <Empty description={t('app.aiNew.noMessages')}/>}
      {turns.map((item) => {
        const state = item.execution?.status ?? item.turn.status;
        const failure = item.turn.rejectionError ?? item.execution?.error;
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
            </Space>
            <div
              style={{
                whiteSpace: 'pre-wrap',
                overflowWrap: 'anywhere',
                margin: '8px 0 16px',
              }}
            >
              {item.turn.input
                .flatMap((message) => message.parts.map((part) => part.text))
                .join('\n')}
            </div>
            <Space>
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
            </Space>
            {item.execution?.status === 'SUCCEEDED' && (
              <div
                style={{
                  whiteSpace: 'pre-wrap',
                  overflowWrap: 'anywhere',
                  marginTop: 8,
                }}
              >
                {item.execution.result?.output
                  .map((part) => part.text)
                  .join('\n')}
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
  );
}
