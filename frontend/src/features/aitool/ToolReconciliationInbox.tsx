import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Input, List, Space} from 'antd';
import type {BrowserContext} from '@/features/ainew/storage';
import {currentAiCredential} from '@/services/arte-ai/request';
import {listToolReconciliations, type ToolReviewDetail,} from '@/services/arte-ai/toolReconciliation';
import ToolReconciliationPanel from './ToolReconciliationPanel';

/** 当前空间责任池；无管理资格时仍可用原编号查看本人的核对进度。 */
export default function ToolReconciliationInbox({
                                                  context,
                                                }: {
  context: BrowserContext;
}) {
  return (
    <InboxContent
      key={`${context.userId}:${context.scope.tenantId}:${context.scope.workspaceId}`}
      context={context}
    />
  );
}

function InboxContent({context}: { context: BrowserContext }) {
  const [records, setRecords] = useState<ToolReviewDetail[]>([]),
    [cursor, setCursor] = useState<string | null>(null);
  const [exhausted, setExhausted] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(false);
  const [draft, setDraft] = useState(''),
    [selected, setSelected] = useState<{ id: string; managed: boolean } | null>(
      null,
    );
  const active = useRef<AbortController | null>(null);
  const load = async (after: string | null = null) => {
    active.current?.abort();
    const c = new AbortController();
    active.current = c;
    const credential = currentAiCredential();
    setBusy(true);
    try {
      const {body} = await listToolReconciliations(context.scope, after, {
        signal: c.signal,
      });
      if (!c.signal.aborted && credential === currentAiCredential()) {
        setRecords(body.data.records);
        setCursor(body.data.nextCursor);
        setExhausted(body.data.exhausted);
        setError(false);
      }
    } catch {
      if (!c.signal.aborted && credential === currentAiCredential()) {
        setRecords([]);
        setError(true);
      }
    } finally {
      if (!c.signal.aborted) setBusy(false);
    }
  };
  useEffect(() => {
    setSelected(null);
    void load();
    return () => active.current?.abort();
  }, [context.userId, context.scope.tenantId, context.scope.workspaceId]);
  return (
    <Flex vertical gap={16}>
      <Alert
        type="info"
        title="核对原业务结果，不重新执行工具"
        description="处理证据需要当前空间核对及资源治理资格，原发起者不能自审。无法判定仍保留 UNKNOWN。"
      />
      <Card
        title="当前空间核对责任池"
        extra={
          <Button loading={busy} onClick={() => void load()}>
            刷新责任池
          </Button>
        }
      >
        {error && (
          <Alert
            type="warning"
            title="无法读取责任池，请检查核对权限及服务开关；也可输入本人原调用编号查看进度。"
          />
        )}
        <List
          dataSource={records}
          loading={busy}
          locale={{emptyText: '当前页没有待核对项'}}
          renderItem={(r) => (
            <List.Item
              actions={[
                <Button
                  key="open"
                  onClick={() =>
                    setSelected({id: r.invocationId, managed: true})
                  }
                >
                  处理证据
                </Button>,
              ]}
            >
              <List.Item.Meta
                title={r.title}
                description={`${r.unassigned ? '尚未认领' : r.responsibleSubjectId} · ${r.state}${r.overdue ? ' · 已超期' : ''}`}
              />
            </List.Item>
          )}
        />
        {!exhausted && (
          <Button disabled={busy} onClick={() => void load(cursor)}>
            下一页核对项
          </Button>
        )}
      </Card>
      <Space.Compact block>
        <Input
          aria-label="原调用编号"
          maxLength={256}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="输入原调用编号查看本人进度"
        />
        <Button
          disabled={!draft.trim()}
          onClick={() => setSelected({id: draft.trim(), managed: false})}
        >
          查看本人进度
        </Button>
        <Button
          disabled={!draft.trim()}
          onClick={() => setSelected({id: draft.trim(), managed: true})}
        >
          按核对权限读取
        </Button>
      </Space.Compact>
      {selected && (
        <ToolReconciliationPanel
          context={context}
          invocationId={selected.id}
          managed={selected.managed}
        />
      )}
    </Flex>
  );
}
