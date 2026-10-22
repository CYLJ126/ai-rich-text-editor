import React, {useEffect, useRef, useState} from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Flex,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type {BrowserContext} from '@/features/ainew/storage';
import type {InvocationState} from '@/services/arte-ai/types';
import {currentAiCredential} from '@/services/arte-ai/request';
import {
  getToolStatistics,
  listToolExecutions,
  type ToolExecutionItem,
  type ToolExecutionPage,
  type ToolObservationQuery,
  type ToolStatistics,
} from '@/services/arte-ai/toolObservation';

const states: { value: InvocationState; label: string }[] = [
  {value: 'ACCEPTED', label: '已受理'},
  {value: 'WAITING_APPROVAL', label: '等待确认／审批'},
  {value: 'QUEUED', label: '排队中'},
  {value: 'RUNNING', label: '执行中'},
  {value: 'SUCCEEDED', label: '成功'},
  {value: 'FAILED', label: '失败'},
  {value: 'CANCELLED', label: '已取消'},
  {value: 'TIMED_OUT', label: '超时'},
  {value: 'INTERRUPTED', label: '已中断'},
  {value: 'UNKNOWN', label: '结果未知'},
];
const stateLabel = (state: InvocationState) =>
  states.find((s) => s.value === state)?.label ?? state;
const localInput = (date: Date) =>
  new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
    .toISOString()
    .slice(0, 19);
const duration = (value: number | null) =>
  value == null ? '暂无可靠数据' : `${value} ms`;
export const toolCostLabel = (item: ToolExecutionItem) =>
  item.billingMode === 'FREE'
    ? '不计费'
    : item.cost.settledAmount != null
      ? `已确定 ${item.cost.settledAmount} ${item.cost.currency}`
      : item.cost.reservedAmount != null
        ? `费用待确定 · 预留 ${item.cost.reservedAmount} ${item.cost.currency}`
        : item.cost.state === 'NOT_RESERVED'
          ? '尚未预留，费用未确定'
          : '费用事实不完整，需核对';

/** 只在首次打开及用户刷新时读取；空间变化卸载旧请求，不自动轮询或重试执行。 */
export default function ToolObservationPanel({
                                               context,
                                             }: {
  context: BrowserContext;
}) {
  return (
    <Content
      key={`${context.userId}:${context.scope.tenantId}:${context.scope.workspaceId}`}
      context={context}
    />
  );
}

function Content({context}: { context: BrowserContext }) {
  const [initial] = useState(() => ({
    from: localInput(new Date(Date.now() - 86_400_000)),
    until: localInput(new Date()),
  }));
  const [page, setPage] = useState<ToolExecutionPage | null>(null);
  const [statistics, setStatistics] = useState<ToolStatistics | null>(null);
  const [committed, setCommitted] = useState<ToolObservationQuery | null>(null);
  const [selected, setSelected] = useState<ToolExecutionItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const [error, setError] = useState<string | null>(null);
  const active = useRef<AbortController | null>(null);

  const load = async (
    query: ToolObservationQuery,
    cursor: string | null = null,
  ) => {
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    const credential = currentAiCredential();
    const valid = () =>
      !controller.signal.aborted &&
      active.current === controller &&
      credential === currentAiCredential();
    setBusy(true);
    setSelected(null);
    setError(null);
    try {
      const [list, stats] = await Promise.all([
        listToolExecutions(context.scope, query, cursor, {
          signal: controller.signal,
        }),
        cursor == null
          ? getToolStatistics(context.scope, query, {
            signal: controller.signal,
          })
          : Promise.resolve(null),
      ]);
      if (!valid()) return;
      setPage(list.body.data);
      if (stats) setStatistics(stats.body.data);
      setCommitted(query);
    } catch {
      if (valid()) {
        setPage(null);
        setStatistics(null);
        setError('无法读取执行记录，请检查当前空间权限或服务状态。');
      }
    } finally {
      if (!controller.signal.aborted && active.current === controller) {
        setBusy(false);
        if (credential !== currentAiCredential()) {
          setPage(null);
          setStatistics(null);
          setSelected(null);
          setError('账号凭据已变化，请重新查询。');
        }
      }
    }
  };

  const query = (values: {
    from: string;
    until: string;
    bindingId?: string;
    toolId?: string;
    state?: InvocationState;
  }) => {
    const from = new Date(values.from),
      until = new Date(values.until);
    if (
      !Number.isFinite(from.getTime()) ||
      !Number.isFinite(until.getTime()) ||
      from >= until ||
      until.getTime() - from.getTime() > 31 * 86_400_000
    ) {
      active.current?.abort();
      setBusy(false);
      setPage(null);
      setStatistics(null);
      setSelected(null);
      setError('请选择有效的起止时间，单次窗口最多 31 天。');
      return;
    }
    void load({
      from: from.toISOString(),
      until: until.toISOString(),
      ...(values.bindingId?.trim()
        ? {bindingId: values.bindingId.trim()}
        : {}),
      ...(values.toolId?.trim() ? {toolId: values.toolId.trim()} : {}),
      ...(values.state ? {state: values.state} : {}),
    });
  };

  useEffect(() => {
    query(initial);
    return () => active.current?.abort();
  }, []);

  const incomplete =
    statistics?.historicalIncomplete || page?.historicalIncomplete;
  return (
    <Flex vertical gap={16}>
      <Alert
        type="info"
        showIcon
        title="本人在当前空间的工具执行记录"
        description="按受理时间筛选，展示本次查询时的状态；查询和刷新不会重新执行工具。时间输入使用浏览器本地时区。"
      />
      <Card title="查询范围">
        <Form
          form={form}
          layout="inline"
          initialValues={initial}
          onFinish={query}
        >
          <Form.Item name="from" label="开始时间" rules={[{required: true}]}>
            <Input aria-label="开始时间" type="datetime-local" step={1}/>
          </Form.Item>
          <Form.Item
            name="until"
            label="结束时间（不含）"
            rules={[{required: true}]}
          >
            <Input aria-label="结束时间" type="datetime-local" step={1}/>
          </Form.Item>
          <Form.Item name="state" label="状态">
            <Select
              aria-label="执行状态"
              allowClear
              options={states}
              style={{minWidth: 150}}
            />
          </Form.Item>
          <Form.Item name="toolId" label="工具 ID">
            <Input
              aria-label="工具 ID"
              maxLength={256}
              placeholder="精确匹配"
            />
          </Form.Item>
          <Form.Item name="bindingId" label="配置 ID">
            <Input
              aria-label="配置 ID"
              maxLength={256}
              placeholder="精确匹配"
            />
          </Form.Item>
          <Form.Item>
            <Button htmlType="submit" type="primary" loading={busy}>
              查询／刷新
            </Button>
          </Form.Item>
          <Form.Item>
            <Button
              disabled={busy}
              onClick={() => {
                const recent = {
                  from: localInput(new Date(Date.now() - 86_400_000)),
                  until: localInput(new Date()),
                };
                form.setFieldsValue({
                  ...recent,
                  toolId: undefined,
                  bindingId: undefined,
                  state: undefined,
                });
                query(recent);
              }}
            >
              最近 24 小时
            </Button>
          </Form.Item>
        </Form>
      </Card>
      {error && <Alert type="warning" showIcon title={error}/>}
      {incomplete && (
        <Alert
          type="warning"
          showIcon
          title="历史记录尚未完整回填，当前结果不覆盖全部历史。请联系管理员完成查询索引升级与回填。"
        />
      )}
      {statistics && (
        <Card title="当前窗口样本统计">
          <Typography.Paragraph>
            已应用窗口：{new Date(statistics.query.from).toLocaleString()} 至{' '}
            {new Date(statistics.query.until).toLocaleString()}（不含）。工具：
            {statistics.query.toolId ?? '全部'}；配置：
            {statistics.query.bindingId ?? '全部'}；状态：
            {statistics.query.state
              ? stateLabel(statistics.query.state)
              : '全部'}
            。
          </Typography.Paragraph>
          {statistics.truncated && (
            <Alert
              type="warning"
              showIcon
              title={`统计已截断：只扫描当前窗口最近 ${statistics.scanLimit} 条候选记录。请缩小时间窗口或按配置筛选。`}
            />
          )}
          <Descriptions
            column={{xs: 1, sm: 2, lg: 4}}
            items={[
              {
                key: 'calls',
                label: '逻辑调用',
                children: statistics.invocations,
              },
              {
                key: 'attempts',
                label: 'Attempt',
                children: statistics.attempts,
              },
              {
                key: 'retries',
                label: '重试次数',
                children: statistics.retries,
              },
              {
                key: 'unknown',
                label: '结果未知',
                children: statistics.states.UNKNOWN ?? 0,
              },
              {
                key: 'rate',
                label: '已确定终态成功率',
                children:
                  statistics.successRate == null
                    ? '暂无已确定终态'
                    : `${statistics.successRate}%`,
              },
              {
                key: 'free',
                label: '不计费调用',
                children: statistics.freeCalls,
              },
              {
                key: 'cost',
                label: '费用未确定',
                children: statistics.unknownCostCalls,
              },
              {
                key: 'zero',
                label: '已知零费用',
                children: statistics.knownZeroCostCalls,
              },
              {
                key: 'p50',
                label: '终态跨度 P50',
                children: duration(statistics.terminalSpanP50Millis),
              },
              {
                key: 'p95',
                label: '终态跨度 P95',
                children: duration(statistics.terminalSpanP95Millis),
              },
              {
                key: 'input',
                label: '已知输入字节',
                children: `${statistics.knownInputBytes}（${statistics.unknownInputBytesCalls} 次未知）`,
              },
              {key: 'output', label: '输出字节', children: '暂无可靠数据'},
            ]}
          />
          <Space wrap>
            {states.map((s) => (
              <Tag key={s.value}>
                {s.label} {statistics.states[s.value] ?? 0}
              </Tag>
            ))}
          </Space>
          {statistics.currencies.map((c) => (
            <p key={c.currency}>
              {c.currency}：已确定部分费用 {c.settledAmount}；当前预留{' '}
              {c.reservedAmount}；未知部分未计入。
            </p>
          ))}
          <Typography.Paragraph type="secondary">
            扫描 {statistics.scanned}／{statistics.scanLimit}{' '}
            条候选，仅统计当前有权读取且符合筛选的调用。成功率分母包含成功、失败、取消、超时和中断，排除
            UNKNOWN 与非终态。费用按币种分别显示，待确定金额不视为零。
          </Typography.Paragraph>
          <Typography.Text type="secondary">
            查询时间：{new Date(statistics.observedAt).toLocaleString()}
            。统计与分页分别读取，状态可能在两次读取间推进。
          </Typography.Text>
        </Card>
      )}
      <Card title="执行记录">
        <Table<ToolExecutionItem>
          rowKey="invocationId"
          loading={busy}
          dataSource={page?.records ?? []}
          pagination={false}
          scroll={{x: 1050}}
          locale={{
            emptyText: error
              ? '查询未成功'
              : '当前页没有可见记录；如有下一页，可继续查找',
          }}
          columns={[
            {
              title: '工具',
              key: 'tool',
              render: (_, r) => (
                <>
                  <Typography.Text>{r.title}</Typography.Text>
                  <br/>
                  <Typography.Text type="secondary">
                    {r.toolId} · {r.toolVersion}
                  </Typography.Text>
                </>
              ),
            },
            {
              title: '状态',
              dataIndex: 'state',
              render: (value: InvocationState) => (
                <Tag color={value === 'UNKNOWN' ? 'warning' : undefined}>
                  {stateLabel(value)}
                </Tag>
              ),
            },
            {
              title: '受理时间',
              dataIndex: 'acceptedAt',
              render: (value: string) => new Date(value).toLocaleString(),
            },
            {
              title: 'Attempt／重试',
              key: 'attempts',
              render: (_, r) => `${r.attemptCount}／${r.retryCount}`,
            },
            {title: '费用', key: 'cost', render: (_, r) => toolCostLabel(r)},
            {
              title: '操作',
              key: 'actions',
              render: (_, r) => (
                <Button onClick={() => setSelected(r)}>查看执行详情</Button>
              ),
            },
          ]}
        />
        {page && (
          <Flex justify="space-between" wrap gap={8} style={{marginTop: 16}}>
            <Typography.Text type="secondary">
              本页扫描 {page.scanned} 条候选。
              {page.exhausted ? '已到范围末尾。' : '还有候选记录可继续读取。'}
            </Typography.Text>
            <Button
              disabled={
                busy || page.exhausted || !page.nextCursor || !committed
              }
              onClick={() => committed && void load(committed, page.nextCursor)}
            >
              下一页记录
            </Button>
          </Flex>
        )}
      </Card>
      <Drawer
        title="执行详情"
        open={!!selected}
        onClose={() => setSelected(null)}
        width={640}
      >
        {selected && (
          <>
            <Alert
              type={selected.state === 'UNKNOWN' ? 'warning' : 'info'}
              title={
                selected.state === 'UNKNOWN'
                  ? '结果未知：需核对原调用，不能直接重做。'
                  : '此处展示查询时的原调用事实；需要最新状态请刷新列表。'
              }
            />
            <Descriptions
              column={1}
              style={{overflowWrap: 'anywhere'}}
              items={[
                {
                  key: 'id',
                  label: '调用编号',
                  children: (
                    <Typography.Text copyable>
                      {selected.invocationId}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'trace',
                  label: '追踪编号',
                  children: (
                    <Typography.Text copyable>
                      {selected.traceId}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'parent',
                  label: '来源',
                  children:
                    selected.source === 'CHAT_CHILD'
                      ? `聊天子调用 · ${selected.parentExecutionId}`
                      : '独立工具调用',
                },
                {
                  key: 'binding',
                  label: '配置编号',
                  children: selected.bindingId,
                },
                {
                  key: 'error',
                  label: '错误码',
                  children: selected.errorCode ?? '无',
                },
                {
                  key: 'cost',
                  label: '费用',
                  children: toolCostLabel(selected),
                },
                {
                  key: 'delay',
                  label: '受理至首次 Attempt',
                  children: duration(selected.timings.admissionToAttemptMillis),
                },
                {
                  key: 'approval',
                  label: '审批等待跨度',
                  children: duration(selected.timings.approvalWaitMillis),
                },
                {
                  key: 'attempt',
                  label: 'Attempt 记录跨度合计',
                  children: duration(selected.timings.attemptSpanMillis),
                },
                {
                  key: 'terminal',
                  label: '受理至当前终态跨度',
                  children: duration(selected.timings.terminalSpanMillis),
                },
                {
                  key: 'input',
                  label: '输入字节',
                  children: selected.inputBytes ?? '未知',
                },
              ]}
            />
            <Typography.Paragraph type="secondary">
              记录跨度可能包含授权、排队、核对和结果保存，不能等同于工具实际执行时间。适配器执行、物理结果提交、输出字节及远端业务计量暂无可独立查询的可靠指标。
            </Typography.Paragraph>
          </>
        )}
      </Drawer>
    </Flex>
  );
}
