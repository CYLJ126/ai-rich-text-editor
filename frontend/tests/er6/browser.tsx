import React from 'react';
import {createRoot} from 'react-dom/client';
import {flushSync} from 'react-dom';
import {fireEvent, getByRole, queryByRole, waitFor} from '@testing-library/dom';
import BusinessChatPanel from '@/features/ainew/BusinessChatPanel';
import {chatConfigSchema, DEFAULT_CHAT_CONFIG} from '@/features/ainew/config';
import locale from '@/locales/zh-CN/aiChat';

declare global {
  interface Window {
    er6Run: () => Promise<unknown>;
    __er6Metrics: any
  }
}
const host = document.getElementById('root')!;
const root = createRoot(host);
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const context = {userId: 'isolated-browser', scope};
const config = chatConfigSchema.parse({
  ...DEFAULT_CHAT_CONFIG, ...scope,
  capabilityId: 'text', capabilityVersion: 'v1', bindingId: 'binding', bindingVersion: 'v1', budgetRef: 'budget'
});
const t = (key: string) => (locale as Record<string, string>)[`app.aiChat.${key}`] ?? key;
const noop = () => {
};
const control = async (action: string, body = {}) => (await (await fetch(`/__er6/${action}`, {
  method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body),
})).json()).data;
const check = (value: unknown, message: string) => {
  if (!value) throw new Error(message);
};
const until = async (condition: () => boolean, message: string, timeout = 15000) => waitFor(() => check(condition(), message), {
  timeout,
  interval: 8
});
const sendButton = () => getByRole(host, 'button', {name: '发送'}) as HTMLButtonElement;
const composer = () => getByRole(host, 'textbox', {name: '输入问题'}) as HTMLTextAreaElement;

// 时间采集仅存在于验收入口，不修改业务 Hook 或开启生产诊断平台。
const transport = window.fetch.bind(window);
window.fetch = async (...args) => {
  const response = await transport(...args);
  if (!String(args[0]).endsWith('/watchInvocation') || !response.ok || !response.body) return response;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const metrics = window.__er6Metrics;
  let buffer = '';
  const transformed = new ReadableStream<Uint8Array>({
    async pull(controller) {
      const value = await reader.read();
      if (value.done) return controller.close();
      buffer += decoder.decode(value.value, {stream: true});
      if (buffer.includes('"kind":"TERMINAL"') && metrics.eventReceived == null)
        metrics.eventReceived = performance.now();
      if (buffer.length > 8192) buffer = buffer.slice(-4096);
      controller.enqueue(value.value);
    }, cancel(reason) {
      return reader.cancel(reason);
    }
  });
  return new Response(transformed, {status: response.status, headers: response.headers});
};

async function mount(index: number, mode: string, offline = false) {
  flushSync(() => root.render(null));
  sessionStorage.clear();
  const conversation = await control('reset', {id: `er6-${index}`, mode, offline});
  window.__er6Metrics = {reads: [], eventReceived: null};
  flushSync(() => root.render(<BusinessChatPanel key={conversation.conversationId} config={config}
                                                 conversation={conversation}
                                                 dirty={false} context={context} options={[]} t={t} onUpdated={noop}
                                                 onConfigurationRejected={noop}
                                                 onChooseModel={noop} onInteraction={noop}
                                                 onOpenToolSettings={noop}/>));
  await until(() => !!queryByRole(host, 'textbox', {name: '输入问题'}), 'composer mounted');
  fireEvent.change(composer(), {target: {value: '下一条独立问题'}});
  await until(() => sendButton().disabled && !composer().disabled, 'running invocation blocks sending');
  await until(() => window.__er6Metrics.reads.some((r: any) => r.url.endsWith('/getInvocationSnapshots')), 'initial authoritative snapshot loaded');
  if (!offline) await until(() => host.textContent!.includes(mode === 'CONNECTION_LOST' ? '这是一段未完成的回复' : '隔离验收的原问题'), 'history/partial output visible');
  return conversation;
}

/** 使用真实 Chrome DOM、React、业务服务及 localhost HTTP/SSE；不代表真实用户任务试用。 */
window.er6Run = async () => {
  check(document.visibilityState === 'visible', 'foreground document required');
  const samples: unknown[] = [];
  const modes = ['HTTP_500', 'CONNECTION_LOST', 'MODEL_TIMEOUT'];
  for (let index = 0; index < 30; index++) {
    const mode = modes[index % modes.length];
    await mount(index, mode);
    await until(() => (window.__er6Metrics.reads.filter((r: any) => r.url.endsWith('/getInvocationSnapshots')).length >= 2), 'SSE initial refresh observed');
    // 确认实际 SSE 已连接后注入终态；不以控制端响应时间冒充事件收到时间。
    await waitFor(async () => check((await control('counters')).streams > 0, 'SSE connected'), {
      timeout: 15000,
      interval: 20
    });
    const before = await control('counters');
    await control('terminal');
    await until(() => window.__er6Metrics.eventReceived !== null && !sendButton().disabled, 'terminal restores independent send');
    // 此时间为 DOM 中按钮已启用后的观察上界；下一帧确认真实页面保持可用。
    const usable = performance.now();
    await new Promise(requestAnimationFrame);
    await new Promise(resolve => setTimeout(resolve, 250));
    check(!sendButton().disabled, 'send remains enabled after recovery settles');
    const metrics = window.__er6Metrics;
    const reads = metrics.reads.filter((r: any) => r.started >= metrics.eventReceived && r.completed <= usable);
    check(reads.some((r: any) => r.url.endsWith('/getConversationExecutionState')), 'occupancy reread required');
    check(reads.some((r: any) => r.url.endsWith('/getInvocationSnapshots')), 'invocation reread required');
    const started = Math.min(...reads.map((r: any) => r.started));
    const completed = Math.max(...reads.map((r: any) => r.completed));
    const after = await control('counters');
    check(after.writes === before.writes, 'automatic recovery must issue zero writes');
    check(after.forbiddenReads === before.forbiddenReads, 'no chat/result/foreign reads beyond scenario');
    check(host.textContent!.includes(t('recovery.turnSkipped')), 'failed turn is explicitly excluded from context');
    if (mode === 'CONNECTION_LOST') check(host.textContent!.includes('这是一段未完成的回复'), 'partial text survives terminal recovery');
    check(started - metrics.eventReceived <= 1000, 'refresh initiation exceeds 1s');
    check(usable - completed <= 500, 'DOM recovery exceeds 500ms');
    samples.push({
      index: index + 1,
      mode,
      eventReceived: metrics.eventReceived,
      readStarted: started,
      readCompleted: completed,
      uiUsable: usable,
      refreshStartMs: started - metrics.eventReceived,
      uiRecoveryMs: usable - completed,
      totalMs: usable - metrics.eventReceived,
      automaticWrites: after.writes - before.writes
    });
  }
  // 真实 DOM 中验证复制及替换确认，不让复制产生服务端写操作。
  const beforeCopy = await control('counters');
  const copy = getByRole(host, 'button', {name: t('recovery.copyQuestion')});
  fireEvent.click(copy);
  await until(() => !!queryByRole(document.body, 'button', {name: t('recovery.keepDraft')}), 'keep-draft confirmation');
  const keep = getByRole(document.body, 'button', {name: t('recovery.keepDraft')});
  await until(() => document.activeElement === keep, 'keep draft is focused by default');
  fireEvent.click(keep);
  check(composer().value === '下一条独立问题', 'existing draft preserved');
  await until(() => !queryByRole(document.body, 'dialog'), 'previous dialog closed');
  fireEvent.click(copy);
  await until(() => !!queryByRole(document.body, 'button', {name: t('recovery.replace')}), 'replace-draft confirmation');
  fireEvent.click(getByRole(document.body, 'button', {name: t('recovery.replace')}));
  await until(() => composer().value === '隔离验收的原问题' && document.activeElement === composer(), 'copy restores composer focus');
  check((await control('counters')).writes === beforeCopy.writes, 'copy must not send');
  fireEvent.keyDown(composer(), {key: 'Enter', shiftKey: true});
  fireEvent.compositionStart(composer());
  fireEvent.keyDown(composer(), {key: 'Enter', isComposing: true});
  fireEvent.compositionEnd(composer());
  check((await control('counters')).writes === beforeCopy.writes, 'Shift+Enter and IME composition must not send');
  // 从费用抽屉关闭后可回到原触发控件；无账单、无指派时不能出现默认金额或写请求。
  await until(() => !queryByRole(document.body, 'dialog'), 'copy dialog closed');
  const fees = getByRole(host, 'button', {name: '查看费用'});
  fees.focus();
  fireEvent.click(fees);
  await until(() => !!queryByRole(document.body, 'dialog', {name: '预算与费用'}), 'cost drawer opened');
  const drawer = getByRole(document.body, 'dialog', {name: '预算与费用'});
  check(drawer.textContent!.includes('未指定核对责任人'), 'honest responsibility status');
  check(drawer.textContent!.includes(t('reconciliation.evidenceUnavailable')), 'missing-evidence limitation visible');
  fireEvent.click(getByRole(drawer, 'button', {name: 'Close'}));
  await until(() => !queryByRole(document.body, 'dialog', {name: '预算与费用'}) && document.activeElement === fees, 'cost drawer restores trigger focus');
  check((await control('counters')).writes === beforeCopy.writes, 'cost inspection cannot assign or settle automatically');
  // SSE 不可用时仍使用生产十秒周期，真实时钟，不加速计时器。
  await mount(31, 'HTTP_500', true);
  const committed = performance.now();
  await control('terminal');
  await until(() => !sendButton().disabled, '10-second fallback restores sending', 13000);
  const fallbackMs = performance.now() - committed;
  check(fallbackMs <= 11000, 'fallback exceeds one 10-second cycle plus local read allowance');
  await new Promise(resolve => setTimeout(resolve, 500));
  check(!sendButton().disabled, 'fallback remains usable after reads settle');
  const finalCounters = await control('counters');
  check(finalCounters.writes === 0 && finalCounters.forbiddenReads === 0, 'no unexpected requests across browser scenarios');
  return {
    browser: navigator.userAgent, visibility: document.visibilityState, clock: 'performance.now, real timers',
    scope: 'Real Chrome + production React/services; isolated simulated HTTP/SSE; no live provider or database',
    samples, fallbackMs, copy: {preserved: true, defaultFocus: true, returnedFocus: true, writes: 0},
    keyboard: {shiftEnterWrites: 0, composingEnterWrites: 0, costsReturnedFocus: true}, counters: finalCounters
  };
};
