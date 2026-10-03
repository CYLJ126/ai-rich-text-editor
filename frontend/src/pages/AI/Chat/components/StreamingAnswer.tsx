import {Button, Space, Spin, Typography} from 'antd';
import {useLayoutEffect, useRef} from 'react';
import {useStreamingText} from '@/features/ai-chat/hooks/useStreamingText';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import {i18nText as t} from '@/utils/i18n';
import AssistantMarkdown from './AssistantMarkdown';

export default function StreamingAnswer({execution, onDisplayChange}: {
  execution: ChatTurnResult['execution'];
  onDisplayChange: () => void;
}) {
  const succeeded = execution?.status === 'SUCCEEDED';
  const streaming = execution?.status === 'ACCEPTED' || execution?.status === 'RUNNING';
  const source = succeeded ? execution.result?.output.map(part => part.text).join('\n') ?? '' : execution?.partialText ?? '';
  const display = useStreamingText(source, streaming, succeeded);
  const previous = useRef(display.text);
  useLayoutEffect(() => {
    if (previous.current !== display.text) onDisplayChange();
    previous.current = display.text;
  }, [display.text, onDisplayChange]);

  return <div style={{marginTop: 8}} aria-busy={display.isTyping}>
    {streaming && !source && <Space role="status"><Spin size="small"/><Typography.Text
      type="secondary">{t('app.aiNew.waitingForReply')}</Typography.Text></Space>}
    {display.text && <AssistantMarkdown text={display.text}/>}
    {!succeeded && display.text && <Typography.Text type="secondary">{t('app.aiNew.partialAnswer')}</Typography.Text>}
    {succeeded && display.isTyping &&
      <Button size="small" type="text" onClick={display.finish}>{t('app.aiNew.showFullAnswer')}</Button>}
  </div>;
}
