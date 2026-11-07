import {Alert, Button, Descriptions, Flex, Input, InputNumber, Popconfirm, Select, Spin, Typography} from 'antd';
import {useEffect, useState} from 'react';
import {type AiScope, getInvocationSnapshot} from '@/services/arte-ai';
import {type AssistantTrial, type AssistantTrialCommand, listAssistantTrials,} from '@/services/arte-ai/assistant';
import {modelOptionKey} from './config';
import {useConfigurationDiscovery, useSelectedBudget} from './useConfigurationSelection';
import {hasAvailableBudget} from './useChatSession';
import {assistantError, useAssistantRead} from './assistantState';
import MarkdownText from './MarkdownText';

const states = {
  ACCEPTED: '已受理',
  WAITING_APPROVAL: '等待审批',
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '已超时',
  INTERRUPTED: '已中断',
  UNKNOWN: '结果待核实',
} as const;
const budgetStates = {
  NOT_RESERVED: '未预留', RESERVED: '已预留', SETTLED: '已结算',
  RELEASED: '已释放', PENDING_RECONCILIATION: '待对账',
} as const;

export default function AssistantTrialPanel({
                                              scope,
                                              assistantId,
                                              revision,
                                              complete,
                                              examples,
                                              canTrial,
                                              canPublish,
                                              alreadyPublished,
                                              disabled,
                                              accepted,
                                              onTrial,
                                              onPublish
                                            }: {
  scope: AiScope;
  assistantId: string;
  revision: number;
  complete: boolean;
  examples: string[];
  canTrial: boolean;
  canPublish: boolean;
  alreadyPublished: boolean;
  disabled: boolean;
  accepted: AssistantTrial | null;
  onTrial: (command: AssistantTrialCommand) => void;
  onPublish: (trialId: string) => void;
}) {
  const discovery = useConfigurationDiscovery();
  const [modelKey, setModelKey] = useState<string>();
  const [budgetRef, setBudgetRef] = useState<string>();
  const [text, setText] = useState('');
  const [input, setInput] = useState<number | null>(null);
  const [output, setOutput] = useState<number | null>(null);
  const [timeout, setTimeoutSeconds] = useState<number | null>(null);
  const model = discovery.options.find(option => modelOptionKey(option) === modelKey);
  const budget = useSelectedBudget(scope, model?.budgetRefs.includes(budgetRef ?? '') ? budgetRef : undefined);

  useEffect(() => {
    if (canTrial) void discovery.load(scope);
  }, [scope, canTrial]);

  const [cursor, setCursor] = useState<string | null>(null);
  const history = useAssistantRead(JSON.stringify([assistantId, cursor, accepted?.trialId]), signal =>
    listAssistantTrials({scope, assistantId, cursor, limit: 20}, {signal}));
  const [chosen, setChosen] = useState<{ forAccepted?: string; trial: AssistantTrial }>();
  const trial = chosen && chosen.forAccepted === accepted?.trialId ? chosen.trial : accepted;
  const snapshot = useAssistantRead(trial?.invocationId ?? null, signal =>
    getInvocationSnapshot({scope, invocationId: trial!.invocationId}, {signal}), true);
  const status = snapshot.value?.status;
  const result = snapshot.value?.result?.kind === 'GENERATION' ? snapshot.value.result.result.value : null;

  const running = !!status && ['ACCEPTED', 'WAITING_APPROVAL', 'QUEUED', 'RUNNING'].includes(status.state);
  useEffect(() => {
    if (!running && status?.budgetState !== 'RESERVED') return;
    const timer = window.setTimeout(snapshot.reload, 1500);
    return () => window.clearTimeout(timer);
  }, [status]);
  useEffect(() => {
    if (status?.budgetState && status.budgetState !== 'NOT_RESERVED') budget.refresh();
  }, [status?.budgetState]);

  const valid = (value: number | null, max: number) => value !== null && Number.isInteger(value) && value >= 1 && value <= max;
  const ready = !!(model && budgetRef && budget.account && hasAvailableBudget(budget.account.available) && text.trim()
    && valid(input, model.limits.maxInputTokens) && valid(output, model.limits.maxOutputTokens)
    && valid(timeout, model.limits.maxTimeoutSeconds) && input! + output! <= model.contextWindowTokens);

  function submit() {
    if (!ready || !model || !budgetRef || input === null || output === null || timeout === null) return;
    onTrial({
      kind: 'trial',
      scope,
      assistantId,
      draftRevision: revision,
      text: text.trim(),
      capability: model.capability,
      binding: model.binding,
      budgetRef,
      maxInputTokens: input,
      generationOptions: {maxOutputTokens: output, temperature: null, topP: null, stopSequences: []},
      timeoutSeconds: timeout,
    });
  }

  return <Flex vertical gap={12}>
    {canTrial && <fieldset disabled={disabled || !complete} style={{padding: 0, border: 0, margin: 0, minWidth: 0}}>
      <Flex vertical gap={8}>
        <Typography.Text strong>单轮付费试用 · 草稿修订 {revision}</Typography.Text>
        {!complete && <Alert type="info" title="填写完整字段和至少一条例题，保存后可试用。"/>}
        {!!examples.length && <Flex align="center" gap={8} wrap>
          {examples.map((example, index) => <Button key={index} size="small" title={example}
                                                    disabled={disabled || !complete || !!text.trim()}
                                                    onClick={() => setText(example + '\n\n')}>
            填入试用示例 {index + 1}
          </Button>)}
        </Flex>}
        <Input.TextArea aria-label="试用输入" placeholder="粘贴本轮任务内容" value={text} maxLength={1_000_000}
                        autoSize={{minRows: 3, maxRows: 8}} onChange={event => setText(event.target.value)}/>
        <Select aria-label="试用模型" placeholder="选择试用模型" disabled={disabled || !complete} value={modelKey}
                loading={discovery.loading}
                options={discovery.options.map(option => ({
                  value: modelOptionKey(option),
                  label: option.displayName + ' · ' + option.binding.id + '@' + option.binding.version,
                }))} onChange={key => {
          const selected = discovery.options.find(option => modelOptionKey(option) === key);
          setModelKey(key);
          setBudgetRef(undefined);
          setInput(selected?.defaults.maxInputTokens ?? null);
          setOutput(selected?.defaults.maxOutputTokens ?? null);
          setTimeoutSeconds(selected?.defaults.timeoutSeconds ?? null);
        }}/>
        {!discovery.loading && !discovery.options.length && <Alert type="info"
                                                                   title={discovery.error ? assistantError(discovery.error) : '当前空间没有可用于试用的模型和预算。'}
                                                                   action={<Button size="small"
                                                                                   onClick={() => void discovery.load(scope)}>重读模型</Button>}/>}
        {model && <>
          <Select aria-label="试用预算账户" placeholder="选择本人预算账户" disabled={disabled || !complete}
                  value={budgetRef}
                  options={model.budgetRefs.map(value => ({value, label: value}))} onChange={setBudgetRef}/>
          {budget.loading && <Spin size="small"/>}
          {!!budget.error && <Alert type="warning" title={assistantError(budget.error)}
                                    action={<Button size="small" onClick={budget.refresh}>重读余额</Button>}/>}
          {budget.account && <>
            <Typography.Text type="secondary">
              可用 {budget.account.available} {budget.account.currency} · 已预留 {budget.account.held}
            </Typography.Text>
            <Typography.Text type="secondary">
              按所选模型及账户费率预留，实际用量由执行结果结算。
            </Typography.Text>
            {!hasAvailableBudget(budget.account.available) &&
              <Alert type="warning" title="预算可用余额不足，请更换账户或补充额度。"/>}
          </>}
          <Flex gap={12} wrap>
            <label>输入 Token 上限 <InputNumber aria-label="输入 Token 上限" precision={0} min={1}
                                                max={model.limits.maxInputTokens} value={input}
                                                onChange={setInput}/></label>
            <label>输出 Token 上限 <InputNumber aria-label="输出 Token 上限" precision={0} min={1}
                                                max={model.limits.maxOutputTokens} value={output} onChange={setOutput}/></label>
            <label>超时（秒） <InputNumber aria-label="试用超时秒数" precision={0} min={1}
                                         max={model.limits.maxTimeoutSeconds} value={timeout}
                                         onChange={setTimeoutSeconds}/></label>
          </Flex>
          {input !== null && output !== null && input + output > model.contextWindowTokens &&
            <Alert type="warning" title={'输入和输出上限之和超过模型上下文窗口 ' + model.contextWindowTokens}/>}
        </>}
        <Popconfirm title="按所选模型与预算账户执行一次付费试用？" okText="确认试用" cancelText="取消" onConfirm={submit}
                    disabled={disabled || !complete || !ready || running || snapshot.loading}>
          <Button type="primary"
                  disabled={disabled || !complete || !ready || running || snapshot.loading}>发起付费试用</Button>
        </Popconfirm>
      </Flex>
    </fieldset>}
    <Flex justify="space-between" align="center" gap={8} wrap>
      <Typography.Text strong>我的试用记录</Typography.Text>
      <Button size="small" onClick={history.reload}>刷新记录</Button>
    </Flex>
    {history.loading && <Spin size="small"/>}
    {!!history.error && <Alert type="warning" title={assistantError(history.error)}/>}
    {history.value && <Select aria-label="试用记录" placeholder="查看一次试用" value={trial?.trialId}
                              options={[...(trial && !history.value.items.some(item => item.trialId === trial.trialId) ? [trial] : []), ...history.value.items]
                                .map(item => ({
                                  value: item.trialId,
                                  label: '修订 ' + item.draftRevision + ' · ' + new Date(item.createdAt).toLocaleString() + ' · ' + item.trialId.slice(0, 8)
                                }))}
                              onChange={key => {
                                const selected = history.value?.items.find(item => item.trialId === key);
                                if (selected) setChosen({forAccepted: accepted?.trialId, trial: selected});
                              }} notFoundContent="暂无试用记录"/>}
    {(cursor || history.value?.nextCursor) && <Flex gap={8}>
      <Button size="small" disabled={!cursor} onClick={() => setCursor(null)}>首页</Button>
      <Button size="small" disabled={!history.value?.nextCursor}
              onClick={() => setCursor(history.value?.nextCursor ?? null)}>下一页</Button>
    </Flex>}
    {trial && <>
      <Flex align="center" gap={8} wrap>
        <Typography.Text>修订 {trial.draftRevision} · {status ? states[status.state] : snapshot.loading ? '读取状态中' : '受理状态待确认'}</Typography.Text>
        <Button size="small" loading={snapshot.loading} onClick={snapshot.reload}>重读试用状态</Button>
      </Flex>
      {!!snapshot.error && <Alert type="warning" title={assistantError(snapshot.error)}/>}
      {status?.error && <Alert type="warning" title={status.error.code} description={status.error.correlationId}/>}
      {status?.budgetState &&
        <Typography.Text type="secondary">费用状态 · {budgetStates[status.budgetState]}</Typography.Text>}
      {result && <>
        <MarkdownText
          text={result.outputs.flatMap(message => message.content.map(part => 'text' in part ? part.text : '')).join('\n')}/>
        <Descriptions size="small" column={{xs: 1, sm: 3}} items={[
          {key: 'input', label: '输入 Token', children: result.usage.inputTokens ?? '未知'},
          {key: 'output', label: '输出 Token', children: result.usage.outputTokens ?? '未知'},
          {key: 'total', label: '总 Token', children: result.usage.totalTokens ?? '未知'},
        ]}/>
      </>}
    </>}
    {canPublish && <Popconfirm title={'发布当前草稿修订 ' + revision + '？'} okText="确认发布" cancelText="取消"
                               disabled={disabled || alreadyPublished || !complete || trial?.draftRevision !== revision || status?.state !== 'SUCCEEDED'}
                               onConfirm={() => trial && onPublish(trial.trialId)}>
      <Button
        disabled={disabled || alreadyPublished || !complete || trial?.draftRevision !== revision || status?.state !== 'SUCCEEDED'}>
        {alreadyPublished ? '此修订已发布' : '发布当前修订'}
      </Button>
    </Popconfirm>}
    {canPublish && !alreadyPublished && (!trial || trial.draftRevision !== revision || status?.state !== 'SUCCEEDED') &&
      <Typography.Text type="secondary">选择一次当前草稿修订的成功试用后即可发布。</Typography.Text>}
  </Flex>;
}
