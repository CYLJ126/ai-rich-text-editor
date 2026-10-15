import React, {useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Alert, Button, Collapse, Descriptions, Drawer, Empty, Flex, Input, Select, Spin, Tag, Typography} from 'antd';
import {
  AiApiError,
  type ChatMessage,
  type ChatModelOption,
  type ConversationResponse,
  type ConversationTitleMetadata
} from '@/services/arte-ai';
import {type ChatTestConfig, DEFAULT_CHAT_CONFIG, modelOptionKey} from './config';
import {type BrowserContext} from './storage';
import {type ConfigurationRejected} from './configurationInvalidation';
import {hasAvailableBudget, isActive, isUserStoppedGeneration, useChatSession} from './useChatSession';
import MarkdownText, {copyText} from './MarkdownText';
import ReconciliationPanel from './ReconciliationPanel';

const messageText = (message: ChatMessage) => message.content.map(part => 'text' in part ? part.text : `[${part.modality}]`).join('\n');

export interface ChatInteractionState {
  pending: boolean;
  active: boolean;
  unsafeToLeave?: boolean
}

export default function BusinessChatPanel({
                                            config,
                                            conversation,
                                            dirty,
                                            context,
                                            options,
                                            t,
                                            onUpdated,
                                            onTitleUpdated,
                                            onConfigurationRejected,
                                            onChooseModel,
                                            onInteraction,
                                            writable = true
                                          }: {
  config: ChatTestConfig | null; conversation: ConversationResponse; dirty: boolean; context: BrowserContext;
  options: ChatModelOption[]; t: (key: string) => string; onUpdated: (value: ConversationResponse) => void;
  onTitleUpdated?: (conversationId: string, value: ConversationTitleMetadata) => void;
  onConfigurationRejected: ConfigurationRejected; onChooseModel: (key: string) => void;
  onInteraction: (state: ChatInteractionState) => void; writable?: boolean;
}) {
  const readingConfig = config ?? {...DEFAULT_CHAT_CONFIG, ...context.scope, temperature: null, topP: null};
  const option = options.find(item => config && item.binding.id === config.bindingId && item.binding.version === config.bindingVersion);
  const chat = useChatSession(readingConfig, conversation, onUpdated, option?.limits.maxInputBytes, onConfigurationRejected, context, !!config && writable, writable, onTitleUpdated);
  const [viewed, setViewed] = useState<Record<string, string>>({});
  const [olderCache, setOlderCache] = useState<{
    version: number;
    turns: typeof chat.turns
  }>({version: conversation.version, turns: []});
  const olderTurns = olderCache.version === conversation.version ? olderCache.turns : [];
  const [notice, setNotice] = useState('');
  const [fees, setFees] = useState(false);
  const [unsafeReconciliation, setUnsafeReconciliation] = useState(false);
  const scroll = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const anchor = useRef<{ height: number; top: number } | null>(null);
  const [newContent, setNewContent] = useState(false);
  const lastVersion = useRef(conversation.version);
  const composing = useRef(false);
  const available = !!chat.budget && hasAvailableBudget(chat.budget.available);
  const pending = !!chat.pending || !!chat.pendingRegeneration;
  const blocked = !config || !writable || dirty || chat.recoveryBlocked || conversation.state !== 'ACTIVE' || chat.historyLoading || !!chat.historyError || chat.submitting || pending || chat.activeIds.length > 0 || chat.unresolved || !available;
  const regenerationBlocked = !writable || dirty || chat.recoveryBlocked || conversation.state !== 'ACTIVE' || chat.historyLoading || !!chat.historyError || chat.submitting || pending || chat.activeIds.length > 0 || chat.unresolved;
  const tooLarge = !!option && new TextEncoder().encode(chat.draft.trim()).length > option.limits.maxInputBytes;
  useEffect(() => {
    onInteraction({
      pending: pending || !!chat.pendingStop || chat.submitting || chat.stopping,
      active: chat.activeIds.length > 0,
      unsafeToLeave: unsafeReconciliation || (!!chat.storageWarning && (pending || !!chat.pendingStop))
    });
  }, [pending, chat.pendingStop, chat.submitting, chat.stopping, chat.activeIds.length, chat.storageWarning, unsafeReconciliation]);
  const allTurns = [...new Map([...olderTurns, ...chat.turns].map(turn => [turn.turnId, turn])).values()].sort((a, b) => a.sequence - b.sequence);
  useEffect(() => {
    if (lastVersion.current !== conversation.version) {
      lastVersion.current = conversation.version;
      setOlderCache({version: conversation.version, turns: []});
      anchor.current = null;
    }
  }, [conversation.version]);
  useLayoutEffect(() => {
    const node = scroll.current;
    if (!node) return;
    if (anchor.current && !chat.historyLoading) {
      node.scrollTop = anchor.current.top + node.scrollHeight - anchor.current.height;
      anchor.current = null;
    } else if (following.current) {
      node.scrollTop = node.scrollHeight;
    } else setNewContent(true);
  }, [chat.turns, chat.invocations, chat.historyLoading]);
  const error = (failure: unknown, retry?: () => void) => failure ?
    <Alert type="error" title={failure instanceof AiApiError ? failure.message : '读取失败，请重试。'}
           action={retry && <Button onClick={retry}>重试</Button>}/> : null;
  const reason = !writable ? '此空间当前只读，不能创建新调用。' : !config ? '请选择模型和兼容预算。' : dirty ? '有未应用配置，请应用或取消修改。' : pending ? '原操作受理结果待确认，请使用原操作恢复按钮。' : chat.activeIds.length ? '当前回复正在生成。' : chat.unresolved ? '执行结果待确认，请刷新或查看待核对费用。' : chat.historyLoading ? '正在恢复历史…' : chat.historyError ? '历史读取失败，请重试。' : !available ? '预算余额未知或不足，请刷新预算。' : '';
  const current = chat.invocationId ? chat.invocations[chat.invocationId] : undefined;
  return <Flex component="section" vertical className="ainew-chat" aria-label="聊天内容">
    <Flex align="center" gap={8} wrap className="ainew-chat-tools">
      <Button loading={chat.historyLoading} onClick={() => {
        setOlderCache({version: conversation.version, turns: []});
        void chat.loadHistory();
      }}>刷新历史</Button>
      <Button onClick={() => setFees(true)}>查看费用</Button>
      <Typography.Text>{chat.budget ? `可用余额 ${chat.budget.available} ${chat.budget.currency}` : '可用余额待查询'}</Typography.Text>
    </Flex>
    <Flex vertical ref={scroll} className="ainew-messages" aria-label="消息历史" onScroll={() => {
      const node = scroll.current!;
      following.current = node.scrollHeight - node.scrollTop - node.clientHeight < 48;
      if (following.current) setNewContent(false);
    }}>
      {chat.page > 1 && <Button disabled={chat.historyLoading} onClick={() => {
        setOlderCache({version: conversation.version, turns: allTurns.slice(0, 90)});
        if (scroll.current) anchor.current = {height: scroll.current.scrollHeight, top: scroll.current.scrollTop};
        following.current = false;
        void chat.loadHistory(chat.page - 1);
      }}>加载较早消息</Button>}
      {error(chat.historyError, () => void chat.loadHistory())}
      {chat.historyLoading && <Flex align="center" gap={8} role="status" className="ainew-loading">
        <Spin size="small"/><Typography.Text>正在读取历史…</Typography.Text>
      </Flex>}
      {!chat.historyLoading && !chat.historyError && !allTurns.length && <Empty description="开始你的第一个问题"/>}
      {allTurns.map(turn => {
        const latestId = turn.invocationIds.at(-1);
        const latest = latestId ? chat.invocations[latestId]?.status : undefined;
        const id = viewed[turn.turnId] ?? (latest && isActive(latest) ? latestId : turn.selectedInvocationId ?? latestId);
        const invocation = id ? chat.invocations[id] : undefined;
        const result = invocation?.result?.kind === 'GENERATION' ? invocation.result.result.value : null;
        const text = result ? result.outputs.map(messageText).join('\n\n') : invocation?.streamText ?? '';
        const regenerate = turn.sequence === chat.total && latest && !isActive(latest) && (latest.state !== 'UNKNOWN' || isUserStoppedGeneration(latest));
        const waitingForReply = !invocation?.status ? chat.historyLoading : isActive(invocation.status);
        return <Flex component="article" vertical className="ainew-turn" key={turn.turnId}>
          <Flex vertical className="ainew-user"><Typography.Text>你</Typography.Text>
            <Typography.Paragraph>{messageText(turn.userMessage)}</Typography.Paragraph></Flex>
          <Flex vertical className="ainew-answer">
            <Flex align="center" gap={8} wrap className="ainew-answer-heading"><Typography.Text
              strong>助手</Typography.Text>
              {invocation?.status &&
                <Tag>{t(isUserStoppedGeneration(invocation.status) ? 'stoppedGeneration' : `execution.${invocation.status.state}`)}</Tag>}
              {result && !result.complete && <Tag color="orange">部分回复</Tag>}
              {turn.invocationIds.length > 1 && <Select aria-label={`浏览第 ${turn.sequence} 轮候选回复`} value={id}
                                                        options={turn.invocationIds.map((candidate, index) => ({
                                                          value: candidate,
                                                          label: `回复 ${index + 1}${candidate === turn.selectedInvocationId ? ' · 后续上下文采用' : ''}`
                                                        }))} onChange={candidate => {
                setViewed(previous => ({...previous, [turn.turnId]: candidate}));
                void chat.inspectCandidate(candidate);
              }}/>}
            </Flex>
            {text ? <MarkdownText text={text} streaming={!result} invocationId={id}/> :
              <Flex align="center" gap={8} className="ainew-reply-placeholder"
                    role={waitingForReply ? 'status' : undefined}>
                {waitingForReply && <Spin size="small"/>}
                <Typography.Text>{!invocation?.status && chat.historyLoading ? '正在读取回复…' : waitingForReply ? '等待模型回复…' : '暂无可用回复'}</Typography.Text>
              </Flex>}
            {!result && text && <Typography.Paragraph type="secondary"
                                                      className="ainew-muted">流式预览；最终结果以服务器记录为准。</Typography.Paragraph>}
            {error(invocation?.error, () => id && void chat.inspectCandidate(id))}
            <Flex align="center" gap={8} wrap className="ainew-answer-actions">
              {text && <Button size="small" onClick={() => void copyText(text, setNotice)}>复制回答</Button>}
              {regenerate && <Button size="small" disabled={regenerationBlocked}
                                     title="沿用原问题、模型、参数及预算，将产生新调用和费用。" onClick={() => {
                setViewed(previous => {
                  const next = {...previous};
                  delete next[turn.turnId];
                  return next;
                });
                void chat.regenerate(latestId);
              }}>重新生成</Button>}
              <Collapse ghost size="small" className="ainew-execution-details"
                        styles={{header: {padding: '8px 0'}, body: {padding: '0 0 8px'}}}
                        items={[{
                          key: 'execution', label: '执行详情', children:
                            <Descriptions column={1} size="small" className="ainew-info" items={[
                              {key: 'invocation', label: '调用', children: id ?? '未知'},
                              ...(result ? [
                                {
                                  key: 'tokens',
                                  label: 'Token',
                                  children: `${result.usage.inputTokens ?? '未知'} / ${result.usage.outputTokens ?? '未知'}`
                                },
                                {key: 'finish', label: '结束原因', children: result.finishReason},
                                {
                                  key: 'model',
                                  label: '实际模型',
                                  children: `${result.model.providerId} / ${result.model.modelId}${result.model.revision ? `@${result.model.revision}` : ''}`
                                },
                              ] : []),
                              ...(invocation?.status?.error ? [{
                                key: 'error',
                                label: '错误',
                                children: `${invocation.status.error.code} · ${invocation.status.error.phase} · ${invocation.status.error.correlationId}`
                              }] : []),
                            ]}/>
                        }]}/>
            </Flex>
          </Flex>
        </Flex>;
      })}
      {current?.streamText && !allTurns.some(turn => chat.invocationId && turn.invocationIds.includes(chat.invocationId)) &&
        <Flex component="article" vertical className="ainew-answer"><Typography.Text strong>助手 ·
          生成中</Typography.Text><MarkdownText text={current.streamText}
                                                invocationId={chat.invocationId ?? undefined}
                                                streaming/></Flex>}
    </Flex>
    {newContent && <Button className="ainew-follow" onClick={() => {
      following.current = true;
      if (scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
      setNewContent(false);
    }}>有新内容 · 回到最新</Button>}
    <Flex vertical gap={8} className="ainew-composer">
      {notice && <Typography.Paragraph role="status">{notice}</Typography.Paragraph>}
      {chat.storageWarning && <Alert type="warning" title={chat.storageWarning}/>}
      {error(chat.submitError)}{error(chat.stopError, chat.retryStop)}
      {chat.pendingStop && !chat.stopError && <Button loading={chat.stopping}
                                                      onClick={chat.retryStop}>重试确认原停止请求</Button>}{error(chat.pollError, chat.resume)}
      {chat.pollPaused && <Button onClick={chat.resume}>恢复执行观察</Button>}
      {pending && <Alert type="warning" title="原操作结果待确认"
                         description="确认时保持原请求和幂等键；当前模型选择不会改写原请求。"/>}
      {chat.pending && <Button loading={chat.submitting} disabled={chat.recoveryBlocked || !writable}
                               onClick={() => void chat.send()}>重试确认原提交</Button>}
      {chat.pendingRegeneration && <Button loading={chat.submitting} disabled={chat.recoveryBlocked || !writable}
                                           onClick={() => void chat.regenerate()}>重试确认原重新生成</Button>}
      <Input.TextArea aria-label="输入问题" placeholder="输入问题，Enter 发送，Shift+Enter 换行"
                      autoSize={{minRows: 2, maxRows: 7}} maxLength={1_000_000} value={chat.draft}
                      onChange={event => chat.setDraft(event.target.value)} onCompositionStart={() => {
        composing.current = true;
      }} onCompositionEnd={() => {
        composing.current = false;
      }}
                      onKeyDown={event => {
                        if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && !composing.current && event.keyCode !== 229) {
                          event.preventDefault();
                          if (!blocked && !tooLarge && chat.draft.trim()) void chat.send();
                        }
                      }}/>
      <Flex align="center" gap={8} wrap className="ainew-compose-actions">
        <Select aria-label="当前发送模型" placeholder="选择模型" value={config ? modelOptionKey({
          binding: {
            type: 'binding',
            id: config.bindingId,
            version: config.bindingVersion
          }
        }) : undefined} options={options.map(item => ({
          value: modelOptionKey(item),
          label: `${item.displayName} · ${item.binding.id}@${item.binding.version}`
        }))} onChange={onChooseModel}/>
        <Typography.Text type="secondary" className="ainew-muted"
                         role="status">{tooLarge ? '本轮文本超过模型输入字节上限。' : reason}</Typography.Text>
        {chat.activeIds.length > 0 &&
          <Button loading={chat.stopping} disabled={chat.stopRequested === chat.activeIds[0]}
                  onClick={() => void chat.stop(chat.activeIds[0])}>{chat.stopRequested ? '正在请求停止' : '停止生成'}</Button>}
        <Button aria-label="发送" type="primary" loading={chat.submitting}
                disabled={blocked || tooLarge || !chat.draft.trim()} onClick={() => void chat.send()}>发送</Button>
      </Flex>
    </Flex>
    <Drawer title="预算与费用" open={fees} forceRender onClose={() => setFees(false)}>
      {config ? <><Button loading={chat.budgetLoading} onClick={() => void chat.loadBudget()}>刷新余额</Button>
        {chat.budget && <Descriptions column={1} size="small" className="ainew-info ainew-budget-info" items={[
          {key: 'available', label: '可用余额', children: `${chat.budget.available} ${chat.budget.currency}`},
          {key: 'limit', label: '额度', children: chat.budget.limit},
          {key: 'held', label: '预留', children: chat.budget.held},
          {key: 'charged', label: '已扣', children: chat.budget.charged},
        ]}/>}
        {error(chat.budgetError, () => void chat.loadBudget())}
        <ReconciliationPanel key={config.budgetRef} context={context} config={config} t={t} writable={writable}
                             onUnsafeToLeave={setUnsafeReconciliation} onConfirmed={chat.refreshAfterReconciliation}/>
      </> : <Typography.Paragraph>选择兼容预算后可查看费用。</Typography.Paragraph>}
      <Typography.Paragraph>单次最终费用未由状态接口提供，不能用余额差推算。未知费用不会补零。</Typography.Paragraph>
    </Drawer>
  </Flex>;
}
