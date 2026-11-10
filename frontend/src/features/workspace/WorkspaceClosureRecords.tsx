import ReconciliationPanel from '@/features/ainew/ReconciliationPanel';
import {DEFAULT_CHAT_CONFIG} from '@/features/ainew/config';
import messages from '@/locales/zh-CN/aiChat';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {Alert, Button, Card, Flex, Input, Pagination, Typography} from 'antd';
import {type ClosureView, getClosureProgress, listClosures} from '@/services/platform/workspaceLifecycle';
import {useLifecycleSession} from './LifecycleSession';
import {closureReason} from './WorkspaceLifecyclePanel';

const states = ['待处理', '处理中', '等待证据', '已软删除', '处理失败'];
export default function WorkspaceClosureRecords({userId}: { userId: string }) {
  const session = useLifecycleSession();
  const [financeUnsafe, setFinanceUnsafe] = useState(false);
  useConfigurationDraft({
    group: 'closure-reconciliation',
    label: '关闭费用核对',
    status: financeUnsafe ? 'pending' : 'clean'
  });
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState(1);
  const [data, setData] = useState<Awaited<ReturnType<typeof listClosures>>>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [selected, setSelected] = useState<{ name: string; view: ClosureView }>();
  useEffect(() => {
    if (!open || !session) return;
    let request: AbortController | undefined;
    const read = () => {
      request?.abort();
      const next = new AbortController();
      request = next;
      void listClosures(current, {signal: next.signal}).then(value => {
        if (!next.signal.aborted) {
          setData(value);
          setError('');
        }
      })
        .catch(() => {
          if (!next.signal.aborted) {
            setData(undefined);
            setError('关闭记录读取失败，请重新读取。');
          }
        });
    };
    read();
    const timer = window.setInterval(read, 5000);
    return () => {
      request?.abort();
      window.clearInterval(timer);
    };
  }, [open, current, retry, session?.revision]);
  useEffect(() => {
    if (!selected) return;
    let request: AbortController | undefined;
    const read = () => {
      request?.abort();
      const next = new AbortController();
      request = next;
      void getClosureProgress(selected.view.scope, selected.view.operationId, {signal: next.signal})
        .then(view => {
          if (!next.signal.aborted) setSelected(value => value ? {...value, view} : value);
        })
        .catch(() => {
          if (!next.signal.aborted) {
            setSelected(undefined);
            setError('当前无法读取此关闭操作，请核对治理资格。');
          }
        });
    };
    read();
    const timer = window.setInterval(read, 5000);
    return () => {
      request?.abort();
      window.clearInterval(timer);
    };
  }, [selected?.view.operationId]);
  if (!session) return null;
  return <Flex vertical gap={8}>
    {session.status}
    <Button disabled={financeUnsafe}
            onClick={() => setOpen(value => !value)}>{open ? '收起关闭记录' : '关闭记录'}</Button>
    {open && <Card title="空间关闭记录" size="small">
      <Flex vertical gap={10}>
        {error && <Alert type="warning" title={error}
                         action={<Button onClick={() => setRetry(value => value + 1)}>重新读取关闭记录</Button>}/>}
        {data?.records.length === 0 && <Typography.Text type="secondary">暂无你有权查看的关闭记录。</Typography.Text>}
        {data?.records.map(item => <Flex key={item.closure.operationId} align="center" justify="space-between" gap={8}
                                         wrap>
          <Typography.Text>{item.workspaceName} · {states[item.closure.state]}</Typography.Text>
          <Button size="small" disabled={financeUnsafe}
                  onClick={() => setSelected({name: item.workspaceName, view: item.closure})}>查看关闭进度</Button>
        </Flex>)}
        {data && <Pagination current={current} pageSize={data.size} total={data.total} showSizeChanger={false}
                             disabled={financeUnsafe} onChange={setCurrent}/>}
        {selected && <Card size="small" title={`${selected.name} · ${states[selected.view.state]}`}>
          <Flex vertical gap={8}>
            {selected.view.state === 3 ?
              <Typography.Text>空间已软删除，普通访问已停止。资源、账本和审计按保留策略保存，本版不提供恢复。</Typography.Text>
              : <Typography.Text>未满足全部检查条件前不会删除空间；未知费用不会按零处理。</Typography.Text>}
            {selected.view.state !== 3 &&
              <ClosureFinance key={selected.view.operationId} userId={userId} view={selected.view}
                              onUnsafe={setFinanceUnsafe}/>}
            {selected.view.blockers.map((blocker, index) => <Alert type="warning" key={index}
                                                                   title={closureReason(blocker.code)}/>)}
          </Flex>
        </Card>}
      </Flex>
    </Card>}
  </Flex>;
}

function ClosureFinance({userId, view, onUnsafe}: {
  userId: string;
  view: ClosureView;
  onUnsafe(unsafe: boolean): void
}) {
  const [budget, setBudget] = useState('');
  const [unsafe, setUnsafe] = useState(false);
  const reportUnsafe = useCallback((value: boolean) => {
    setUnsafe(value);
    onUnsafe(value);
  }, [onUnsafe]);
  const [selected, setSelected] = useState('');
  const context = useMemo(() => ({userId, scope: view.scope}), [userId, view.scope.tenantId, view.scope.workspaceId]);
  const config = useMemo(() => ({
    ...DEFAULT_CHAT_CONFIG, ...view.scope,
    budgetRef: selected,
    temperature: null,
    topP: null
  }), [view.scope.tenantId, view.scope.workspaceId, selected]);
  const t = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
  return <Flex vertical gap={8}>
    <Typography.Text
      type="secondary">核对费用仍需该预算账户的明确权限、真实账单和执行结束证据；空间治理身份不自动授予费用核对权。</Typography.Text>
    <Flex gap={8} wrap><Input aria-label="待核对预算账户" disabled={unsafe} value={budget} maxLength={256}
                              placeholder="预算账户"
                              onChange={event => setBudget(event.target.value)} style={{maxWidth: 280}}/>
      <Button disabled={unsafe || !budget.trim()}
              onClick={() => setSelected(budget.trim())}>打开费用核对</Button></Flex>
    {selected && <ReconciliationPanel key={selected} context={context}
                                      config={config} t={t}
                                      accountRef={{
                                        tenantId: view.scope.tenantId,
                                        type: 'budget-account',
                                        resourceId: selected
                                      }}
                                      onUnsafeToLeave={reportUnsafe} onConfirmed={async () => {
    }}/>}
  </Flex>;
}
