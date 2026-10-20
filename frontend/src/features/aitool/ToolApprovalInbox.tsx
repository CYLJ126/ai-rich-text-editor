import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Input, List, Space, Typography} from 'antd';
import type {BrowserContext} from '@/features/ainew/storage';
import {currentAiCredential} from '@/services/arte-ai/request';
import {type ApprovalSummary, listPendingApprovals} from '@/services/arte-ai/toolApproval';
import ToolApprovalPanel from './ToolApprovalPanel';
import {toolFailure} from './useToolCommand';

/** 参与者待办按当前范围分页；可用审批编号恢复历史，编号本身不授予读取权。 */
export default function ToolApprovalInbox({context}: { context: BrowserContext }) {
  const [records, setRecords] = useState<ApprovalSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [exhausted, setExhausted] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const active = useRef<AbortController | null>(null);
  const load = async (after: string | null = null) => {
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    const credential = currentAiCredential();
    setBusy(true);
    setError(null);
    try {
      const {body} = await listPendingApprovals(context.scope, after, {signal: controller.signal});
      if (controller.signal.aborted || credential !== currentAiCredential()) return;
      setRecords(body.data.records);
      setCursor(body.data.nextCursor);
      setExhausted(body.data.exhausted);
    } catch (failure) {
      if (!controller.signal.aborted && credential === currentAiCredential()) {
        setRecords([]);
        setError(toolFailure(failure));
      }
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };
  useEffect(() => {
    setSelected(null);
    setRecords([]);
    void load();
    return () => active.current?.abort();
  }, [context.userId, context.scope]);
  return <Flex vertical gap={16}>
    <Alert type="info" title="当前空间的人工确认与审批"
           description="仅展示您参与且当前仍有资格查看的待办。本人确认由原发起者决定；独立审批由指定的其他主体决定。"/>
    <Card size="small" title="待处理" extra={<Button loading={busy} onClick={() => void load()}>刷新待办</Button>}>
      {error && <Alert type="warning" title={error}/>}
      <List loading={busy} dataSource={records} locale={{emptyText: '当前页没有可见待办。可刷新，或继续扫描下一页。'}}
            renderItem={item => <List.Item
              actions={[<Button key="open" onClick={() => setSelected(item.approvalId)}>查看固定内容</Button>]}>
              <List.Item.Meta title={item.title} description={<Space direction="vertical">
                <Typography.Text>{item.mode === 'REQUESTER_CONFIRMATION' ? '本人确认' : '独立审批'} · {item.mayDecide ? '可决定' : '等待其他审批主体'}</Typography.Text>
                <Typography.Text>截止：{item.expiresAt}</Typography.Text>
              </Space>}/>
            </List.Item>}/>
      {!exhausted && <Button disabled={busy} onClick={() => void load(cursor)}>下一页</Button>}
    </Card>
    <Space.Compact block>
      <Input aria-label="审批编号" placeholder="输入审批编号以恢复查看" maxLength={256} value={draft}
             onChange={event => setDraft(event.target.value)}/>
      <Button disabled={!draft.trim()} onClick={() => setSelected(draft.trim())}>读取审批</Button>
    </Space.Compact>
    {selected &&
      <ToolApprovalPanel key={selected} context={context} approvalId={selected} onDecided={() => void load()}/>}
  </Flex>;
}
