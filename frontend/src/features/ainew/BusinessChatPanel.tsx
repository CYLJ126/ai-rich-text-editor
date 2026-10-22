import React, {useEffect, useLayoutEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';
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
import {useLocatedHistory} from './useLocatedHistory';
import type {LocatedHistory} from './searchNavigation';
import {
  candidateKey,
  runTerminal,
  type ToolChatChoice,
  type ToolChatStatus,
  turnCandidates
} from '@/services/arte-ai/toolChat';
import ToolChatRunPanel, {type RunPreparation} from './ToolChatRunPanel';
import ToolChatSettings from './ToolChatSettings';
import {useToolChatCommand} from './useToolChatCommand';

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
                                            onOpenToolSettings,
                                            toolSettingsContainer,
                                            toolSettingsActive = false,
                                            writable = true,
                                            location,
                                            onReturnLatest,
                                            onLocationInvalidated
                                          }: {
  config: ChatTestConfig | null; conversation: ConversationResponse; dirty: boolean; context: BrowserContext;
  options: ChatModelOption[]; t: (key: string) => string; onUpdated: (value: ConversationResponse) => void;
  onTitleUpdated?: (conversationId: string, value: ConversationTitleMetadata) => void;
  onConfigurationRejected: ConfigurationRejected; onChooseModel: (key: string) => void;
  onInteraction: (state: ChatInteractionState) => void; writable?: boolean;
  onOpenToolSettings: () => void;
  toolSettingsContainer?: HTMLElement | null;
  toolSettingsActive?: boolean;
  location?: LocatedHistory | null; onReturnLatest?: () => void;
  onLocationInvalidated?: (error: unknown, target: LocatedHistory) => void;
}) {
  const readingConfig = config ?? {...DEFAULT_CHAT_CONFIG, ...context.scope, temperature: null, topP: null};
  const option = options.find(item => config && item.binding.id === config.bindingId && item.binding.version === config.bindingVersion);
  const chat = useChatSession(readingConfig, conversation, onUpdated, option?.limits.maxInputBytes, onConfigurationRejected, context, !!config && writable, writable, onTitleUpdated);
  const located = useLocatedHistory(location, conversation, context.scope, (error, target) => onLocationInvalidated?.(error, target));
  const [toolChoice, setToolChoice] = useState<ToolChatChoice | null>(null);
  const [runContentRevision, setRunContentRevision] = useState(0);
  const [runStates, setRunStates] = useState<Record<string, { status: ToolChatStatus | null; error?: unknown }>>({});
  const toolCommand = useToolChatCommand(context, conversation.conversationId, (_id, text) => {
    chat.setDraft(previous => previous.trim() === text ? '' : previous);
    chat.refreshAfterExternalExecution();
  });
  const runGateIds = [...new Set([...chat.turns.flatMap(turn => {
    const latest = turnCandidates(turn).at(-1);
    return latest?.kind === 'TOOL_CHAT_RUN' ? [latest.executionId] : [];
  }), ...Object.entries(runStates).flatMap(([id, value]) => value.status && !runTerminal(value.status.state) ? [id] : []),
    ...(toolCommand.stop ? [toolCommand.stop.runId] : []), ...(toolCommand.saved?.acceptedId ? [toolCommand.saved.acceptedId] : [])])];
  const runBusy = runGateIds.some(id => !runStates[id]?.status || !!runStates[id].error || !runTerminal(runStates[id].status?.state ?? 'ACCEPTED'));
  const toolPending = !!toolCommand.saved && !toolCommand.saved.acceptedId;
  const toolBlocked = toolCommand.blocked || toolCommand.storageError || toolCommand.busy || toolPending || runBusy || !!toolCommand.stop;
  const toolModelUnsupported = !!toolChoice && option?.toolCallsSupported !== true;
  const toolChoiceInvalid = !!toolChoice && (!toolChoice.toolBindingIds.length || !chat.budget || !toolChoice.budgetLimits.some(b => b.currency === chat.budget?.currency) || toolChoice.budgetLimits.some(b => !/^[0-9]{1,18}(\.[0-9]{1,18})?$/.test(b.amount)));

  function sendMessage() {
    if (!toolChoice) {
      void chat.send();
      return;
    }
    if (!config || !chat.budget || toolBlocked || toolChoiceInvalid || toolModelUnsupported) return;
    void toolCommand.send({
      scope: context.scope,
      conversationId: conversation.conversationId,
      expectedVersion: conversation.version,
      text: chat.draft.trim(),
      binding: {type: 'binding', id: config.bindingId, version: config.bindingVersion},
      capability: {type: 'capability', id: config.capabilityId, version: config.capabilityVersion},
      modelBudgetRef: config.budgetRef,
      maxInputTokens: config.maxInputTokens,
      generationOptions: {
        maxOutputTokens: config.maxOutputTokens,
        temperature: config.temperature,
        topP: config.topP,
        stopSequences: []
      },
      timeoutSeconds: config.timeoutSeconds,
      toolBindingIds: toolChoice.toolBindingIds,
      ...(toolChoice.toolBudgetRefs ? {toolBudgetRefs: toolChoice.toolBudgetRefs} : {}),
      maxModelRounds: toolChoice.maxModelRounds,
      maxToolCalls: toolChoice.maxToolCalls,
      maxConcurrentTools: toolChoice.maxConcurrentTools,
      budgetLimits: toolChoice.budgetLimits
    }, !!toolChoice.readOnly);
  }

  function prepareRun(value: RunPreparation) {
    if (value.kind === 'rewrite') {
      setToolChoice(null);
      chat.setDraft(`请只改写以下历史回答，保留事实并改善表达。不调用工具、不读取最新数据。旧回答生成于 ${value.actions.answeredAt}，本次未核实来源时效。\n原问题：${value.actions.text}\n历史回答：\n${value.answer}`);
    } else {
      setToolChoice({
        toolBindingIds: value.actions.readOnlyBindingIds,
        readOnly: true,
        maxModelRounds: 8,
        maxToolCalls: 16,
        maxConcurrentTools: 2,
        budgetLimits: [{currency: chat.budget?.currency ?? 'CNY', amount: '1.00'}]
      });
      chat.setDraft(value.actions.text);
    }
  }

  function renderRun(id: string, allowActions = true) {
    return <ToolChatRunPanel key={id} context={context} runId={id} allowActions={allowActions}
                             onContent={() => setRunContentRevision(n => n + 1)}
                             onStatus={(status, error) => {
                               if (status) toolCommand.observed(status);
                               setRunStates(previous => {
                                 const old = previous[id];
                                 if (old?.status?.version === status?.version && old?.status?.cancellationRequested === status?.cancellationRequested && old?.error === error) return previous;
                                 return {...previous, [id]: {status, error}};
                               });
                             }} onSettled={() => {
      if (allowActions || toolCommand.saved?.acceptedId === id) chat.refreshAfterExternalExecution();
    }}
                             onStop={() => void toolCommand.cancel(id)} stopPending={toolCommand.stop?.runId === id}
                             disabled={!writable || dirty || toolBlocked || chat.activeIds.length > 0 || !!chat.pending || !!chat.pendingRegeneration}
                             onPrepare={prepareRun}/>;
  }
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
  const blocked = toolBlocked || toolChoiceInvalid || toolModelUnsupported || !config || !writable || dirty || chat.recoveryBlocked || conversation.state !== 'ACTIVE' || chat.historyLoading || !!chat.historyError || chat.submitting || pending || chat.activeIds.length > 0 || chat.unresolved || !available;
  const regenerationBlocked = toolBlocked || !writable || dirty || chat.recoveryBlocked || conversation.state !== 'ACTIVE' || chat.historyLoading || !!chat.historyError || chat.submitting || pending || chat.activeIds.length > 0 || chat.unresolved;
  const tooLarge = !!option && new TextEncoder().encode(chat.draft.trim()).length > option.limits.maxInputBytes;
  useEffect(() => {
    onInteraction({
      pending: toolPending || toolCommand.busy || !!toolCommand.stop || pending || !!chat.pendingStop || chat.submitting || chat.stopping,
      active: runBusy || chat.activeIds.length > 0,
      unsafeToLeave: toolCommand.storageError || unsafeReconciliation || (!!chat.storageWarning && (pending || !!chat.pendingStop))
    });
  }, [pending, chat.pendingStop, chat.submitting, chat.stopping, chat.activeIds.length, chat.storageWarning, unsafeReconciliation, toolPending, toolCommand.busy, toolCommand.stop, toolCommand.storageError, runBusy]);
  const allTurns = located.active ? located.turns : [...new Map([...olderTurns, ...chat.turns].map(turn => [turn.turnId, turn])).values()].sort((a, b) => a.sequence - b.sequence);
  const visibleViews = located.active ? located.views : chat.invocations;
  const locatedAnchor = useRef<{ id: string; offset: number } | null>(null);
  const positioned = useRef<string | null>(null);
  const [highlight, setHighlight] = useState<string | null>(null);

  function captureLocatedAnchor() {
    const node = scroll.current;
    if (!node) return;
    const top = node.getBoundingClientRect().top;
    const turn = [...node.querySelectorAll<HTMLElement>('[data-turn-id]')].find(value => value.getBoundingClientRect().bottom > top);
    if (turn) {
      locatedAnchor.current = {id: turn.dataset.turnId!, offset: turn.getBoundingClientRect().top - top};
      located.anchor(turn.dataset.turnId!);
    }
  }

  useLayoutEffect(() => {
    const node = scroll.current;
    if (!node || !located.active || !location) return;
    following.current = false;
    if (positioned.current !== location.id) {
      const target = [...node.querySelectorAll<HTMLElement>('[data-message-id]')].find(value => value.dataset.messageId === location.messageId);
      if (target) {
        node.scrollTop += target.getBoundingClientRect().top - node.getBoundingClientRect().top - 20;
        positioned.current = location.id;
        setHighlight(location.messageId);
        captureLocatedAnchor();
      }
    } else if (locatedAnchor.current) {
      const saved = locatedAnchor.current;
      const turn = [...node.querySelectorAll<HTMLElement>('[data-turn-id]')].find(value => value.dataset.turnId === saved.id);
      if (turn) node.scrollTop += turn.getBoundingClientRect().top - node.getBoundingClientRect().top - saved.offset;
    }
  }, [location?.id, located.revision, located.active, runContentRevision]);
  useEffect(() => {
    if (!highlight) return;
    const timer = setTimeout(() => setHighlight(null), 2400);
    return () => clearTimeout(timer);
  }, [highlight, location?.id]);
  const wasLocated = useRef(located.active);
  useEffect(() => {
    if (wasLocated.current && !located.active) {
      following.current = true;
      positioned.current = null;
      setHighlight(null);
      void chat.loadHistory();
    }
    wasLocated.current = located.active;
  }, [located.active]);
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
    if (located.active) {
      setNewContent(true);
      return;
    }
    if (anchor.current && !chat.historyLoading) {
      node.scrollTop = anchor.current.top + node.scrollHeight - anchor.current.height;
      anchor.current = null;
    } else if (following.current) {
      node.scrollTop = node.scrollHeight;
    } else setNewContent(true);
  }, [chat.turns, chat.invocations, chat.historyLoading, runStates, runContentRevision, located.active]);
  const error = (failure: unknown, retry?: () => void) => failure ?
    <Alert type="error" title={failure instanceof AiApiError ? failure.message : '读取失败，请重试。'}
           action={retry && <Button onClick={retry}>重试</Button>}/> : null;
  const reason = toolPending ? '原工具聊天提交结果待确认，请继续确认原请求。' : runBusy ? '原工具聊天正在执行或状态待读取。' : toolModelUnsupported ? '当前模型不支持工具调用，请选择支持工具调用的模型，或关闭工具。' : toolChoiceInvalid ? '请选择工具并填写模型及工具所需币种的本次费用上限。' : !writable ? '此空间当前只读，不能创建新调用。' : !config ? '请选择模型和兼容预算。' : dirty ? '有未应用配置，请应用或取消修改。' : pending ? '原操作受理结果待确认，请使用原操作恢复按钮。' : chat.activeIds.length ? '当前回复正在生成。' : chat.unresolved ? '执行结果待确认，请刷新或查看待核对费用。' : chat.historyLoading ? '正在恢复历史…' : chat.historyError ? '历史读取失败，请重试。' : !available ? '预算余额未知或不足，请刷新预算。' : '';
  const current = chat.invocationId ? chat.invocations[chat.invocationId] : undefined;
  return <Flex component="section" vertical className="ainew-chat" aria-label="聊天内容">
    <Flex align="center" gap={8} wrap className="ainew-chat-tools">
      <Button loading={located.active ? located.loading : chat.historyLoading} onClick={() => {
        if (located.active) {
          captureLocatedAnchor();
          void located.rebuild();
          return;
        }
        setOlderCache({version: conversation.version, turns: []});
        void chat.loadHistory();
      }}>刷新历史</Button>
      <Button onClick={() => setFees(true)}>查看费用</Button>
      <Typography.Text>{chat.budget ? `可用余额 ${chat.budget.available} ${chat.budget.currency}` : '可用余额待查询'}</Typography.Text>
    </Flex>
    {located.active && <Alert type="info" title="正在浏览定位的历史消息" action={<Button onClick={() => {
      located.dismiss();
      onReturnLatest?.();
    }}>返回最新聊天</Button>}/>}
    <Flex vertical ref={scroll} className="ainew-messages" aria-label="消息历史" onScroll={() => {
      const node = scroll.current!;
      if (located.active) {
        following.current = false;
        captureLocatedAnchor();
        return;
      }
      following.current = node.scrollHeight - node.scrollTop - node.clientHeight < 48;
      if (following.current) setNewContent(false);
    }}>
      {located.active && located.before && <Button loading={located.loading} onClick={() => {
        captureLocatedAnchor();
        void located.load('before');
      }}>加载定位历史的较早消息</Button>}
      {located.active && error(located.error, () => {
        captureLocatedAnchor();
        void located.rebuild();
      })}
      {!located.active && chat.page > 1 && <Button disabled={chat.historyLoading} onClick={() => {
        setOlderCache({version: conversation.version, turns: allTurns.slice(0, 90)});
        if (scroll.current) anchor.current = {height: scroll.current.scrollHeight, top: scroll.current.scrollTop};
        following.current = false;
        void chat.loadHistory(chat.page - 1);
      }}>加载较早消息</Button>}
      {!located.active && error(chat.historyError, () => void chat.loadHistory())}
      {(located.active ? located.loading : chat.historyLoading) &&
        <Flex align="center" gap={8} role="status" className="ainew-loading">
          <Spin size="small"/><Typography.Text>正在读取历史…</Typography.Text>
        </Flex>}
      {!chat.historyLoading && !chat.historyError && !allTurns.length && <Empty description="开始你的第一个问题"/>}
      {allTurns.map(turn => {
        const candidates = turnCandidates(turn);
        const latestCandidate = candidates.at(-1);
        const latestRun = latestCandidate?.kind === 'TOOL_CHAT_RUN' ? latestCandidate : null;
        const selected = turn.selectedCandidate ?? candidates.find(c => c.executionId === turn.selectedInvocationId) ?? latestCandidate;
        const viewedKey = viewed[turn.turnId] ?? (located.active && located.viewed[turn.turnId] ? `INVOCATION:${located.viewed[turn.turnId]}` : undefined);
        const latestStatus = latestCandidate?.kind === 'INVOCATION' ? visibleViews[latestCandidate.executionId]?.status : undefined;
        const activeCandidate = latestRun && !runTerminal(runStates[latestRun.executionId]?.status?.state ?? 'ACCEPTED')
          ? latestRun : latestStatus && isActive(latestStatus) ? latestCandidate : selected;
        const candidate = candidates.find(c => candidateKey(c) === viewedKey || c.executionId === viewedKey)
          ?? (located.active && location?.turnId === turn.turnId && location.candidate ? location.candidate : activeCandidate);
        const runCandidate = candidate?.kind === 'TOOL_CHAT_RUN' ? candidate : null;
        const latestId = turn.invocationIds.at(-1);
        const latest = latestId ? visibleViews[latestId]?.status : undefined;
        const id = (located.active ? located.viewed[turn.turnId] : candidate?.kind === 'INVOCATION' ? candidate.executionId : undefined) ?? (latest && isActive(latest) ? latestId : turn.selectedInvocationId ?? latestId);
        const invocation = id ? visibleViews[id] : undefined;
        const result = invocation?.result?.kind === 'GENERATION' ? invocation.result.result.value : null;
        const text = result ? result.outputs.map(messageText).join('\n\n') : invocation?.streamText ?? '';
        const regenerate = !located.active && turn.sequence === chat.total && latest && !isActive(latest) && (latest.state !== 'UNKNOWN' || isUserStoppedGeneration(latest));
        const waitingForReply = !invocation?.status ? chat.historyLoading : isActive(invocation.status);
        return <Flex component="article" vertical className="ainew-turn" key={turn.turnId} data-turn-id={turn.turnId}>
          <Flex vertical className={`ainew-user${highlight === turn.userMessage.messageId ? ' is-search-target' : ''}`}
                data-message-id={turn.userMessage.messageId}><Typography.Text>你</Typography.Text>
            <Typography.Paragraph>{messageText(turn.userMessage)}</Typography.Paragraph></Flex>
          <Flex vertical className="ainew-answer">
            <Flex align="center" gap={8} wrap className="ainew-answer-heading"><Typography.Text
              strong>助手</Typography.Text>
              {invocation?.status &&
                <Tag>{t(isUserStoppedGeneration(invocation.status) ? 'stoppedGeneration' : `execution.${invocation.status.state}`)}</Tag>}
              {result && !result.complete && <Tag color="orange">部分回复</Tag>}
              {candidates.length > 1 && <Select aria-label={`浏览第 ${turn.sequence} 轮候选回复`}
                                                value={candidate ? candidateKey(candidate) : id}
                                                options={candidates.map((candidate, index) => ({
                                                  value: candidateKey(candidate),
                                                  label: `回复 ${index + 1}${candidate.executionId === selected?.executionId ? ' · 后续上下文采用' : ''}`
                                                        }))} onChange={candidate => {
                if (located.active) {
                  const chosen = candidates.find(c => candidateKey(c) === candidate);
                  if (chosen?.kind === 'INVOCATION') void located.choose(turn.turnId, chosen.executionId);
                  else setViewed(previous => ({...previous, [turn.turnId]: candidate}));
                  return;
                }
                setViewed(previous => ({...previous, [turn.turnId]: candidate}));
                const chosen = candidates.find(c => candidateKey(c) === candidate);
                if (chosen?.kind === 'INVOCATION') void chat.inspectCandidate(chosen.executionId);
              }}/>}
            </Flex>
            {runCandidate ? renderRun(runCandidate.executionId, !located.active && turn.sequence === chat.total) : <>
            {result ? result.outputs.map(message => <div key={message.messageId} data-message-id={message.messageId}
                                                         className={highlight === message.messageId ? 'is-search-target' : undefined}>
              <MarkdownText text={messageText(message)} invocationId={id}/></div>) : text ?
              <MarkdownText text={text} streaming invocationId={id}/> :
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
            </>}
            {latestRun && latestRun.executionId !== runCandidate?.executionId &&
              <div hidden>{renderRun(latestRun.executionId, false)}</div>}
          </Flex>
        </Flex>;
      })}
      {toolCommand.saved?.acceptedId && !allTurns.some(turn => turnCandidates(turn).some(c => c.kind === 'TOOL_CHAT_RUN' && c.executionId === toolCommand.saved?.acceptedId)) && renderRun(toolCommand.saved.acceptedId, false)}
      {runGateIds.filter(id => id !== toolCommand.saved?.acceptedId && !allTurns.some(turn => turnCandidates(turn).some(c => c.kind === 'TOOL_CHAT_RUN' && c.executionId === id))).map(id =>
        <div hidden key={id}>{renderRun(id, false)}</div>)}
      {located.active && located.after && <Button loading={located.loading} onClick={() => {
        captureLocatedAnchor();
        void located.load('after');
      }}>加载定位历史的较晚消息</Button>}
      {!located.active && current?.streamText && !allTurns.some(turn => chat.invocationId && turn.invocationIds.includes(chat.invocationId)) &&
        <Flex component="article" vertical className="ainew-answer"><Typography.Text strong>助手 ·
          生成中</Typography.Text><MarkdownText text={current.streamText}
                                                invocationId={chat.invocationId ?? undefined}
                                                streaming/></Flex>}
    </Flex>
    {!located.active && newContent && <Button className="ainew-follow" onClick={() => {
      following.current = true;
      if (scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
      setNewContent(false);
    }}>有新内容 · 回到最新</Button>}
    <Flex vertical gap={8} className="ainew-composer">
      {toolCommand.storageError &&
        <Alert type="warning" title="工具聊天恢复记录无法安全保存或读取，请保留页面并检查浏览器存储。"
               action={!toolCommand.blocked &&
                 <Button onClick={toolCommand.recheckStorage}>重新检查恢复存储</Button>}/>}
      {toolCommand.error != null && <Alert type="warning" title="工具聊天请求未能确认"
                                           description={toolCommand.error instanceof AiApiError && toolCommand.error.code === '205003'
                                             ? '当前模型或网关不支持工具调用，请选择支持工具调用的模型后重新发送。'
                                             : toolCommand.error instanceof Error ? toolCommand.error.message : '请确认原请求。'}/>}
      {toolPending && <Button loading={toolCommand.busy} disabled={toolCommand.blocked || !writable}
                              onClick={() => void toolCommand.send()}>继续确认原工具聊天提交</Button>}
      {toolCommand.stop &&
        <Button loading={toolCommand.busy} disabled={toolCommand.blocked} onClick={() => void toolCommand.cancel()}>继续确认原
          Run 停止请求</Button>}
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
                          if (!blocked && !tooLarge && chat.draft.trim()) sendMessage();
                        }
                      }}/>
      <Flex align="center" gap={8} wrap className="ainew-compose-actions">
        <Button disabled={toolBlocked || pending || chat.activeIds.length > 0 || !writable}
                onClick={onOpenToolSettings}>
          {toolChoice ? `工具已开启 · ${toolChoice.toolBindingIds.length} 项` : '工具设置'}
        </Button>
        <Select aria-label="当前发送模型" placeholder="选择模型" value={config ? modelOptionKey({
          binding: {
            type: 'binding',
            id: config.bindingId,
            version: config.bindingVersion
          }
        }) : undefined} options={options.map(item => ({
          value: modelOptionKey(item),
          label: `${item.displayName} · ${item.binding.id}@${item.binding.version}${item.toolCallsSupported ? ' · 支持工具调用' : ''}`
        }))} onChange={onChooseModel}/>
        <Typography.Text type="secondary" className="ainew-muted"
                         role="status">{tooLarge ? '本轮文本超过模型输入字节上限。' : reason}</Typography.Text>
        {chat.activeIds.length > 0 &&
          <Button loading={chat.stopping} disabled={chat.stopRequested === chat.activeIds[0]}
                  onClick={() => void chat.stop(chat.activeIds[0])}>{chat.stopRequested ? '正在请求停止' : '停止生成'}</Button>}
        <Button aria-label="发送" type="primary" loading={chat.submitting || toolCommand.busy}
                disabled={blocked || tooLarge || !chat.draft.trim()} onClick={sendMessage}>发送</Button>
      </Flex>
    </Flex>
    {/* 表单呈现在侧栏，选择与执行限制仍由当前会话统一持有。 */}
    {toolSettingsContainer && createPortal(
      <ToolChatSettings context={context} active={toolSettingsActive} value={toolChoice} onChange={setToolChoice}
                        disabled={toolBlocked || pending || chat.activeIds.length > 0 || !writable}
                        currency={chat.budget?.currency} modelSupportsTools={option?.toolCallsSupported === true}/>,
      toolSettingsContainer,
    )}
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
