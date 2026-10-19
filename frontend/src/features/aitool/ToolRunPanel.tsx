import React, {useEffect, useRef, useState} from 'react';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import type {BrowserContext} from '@/features/ainew/storage';
import {getTool, getToolBinding, type ToolBinding, type ToolSchema,} from '@/services/arte-ai/tool';
import {
  confirmedToolRequest,
  previewToolInvocation,
  type ToolInvocationPreview,
  toolInvocationRequest,
} from '@/services/arte-ai/toolInvocation';
import {currentAiCredential} from '@/services/arte-ai/request';
import ConfigurationEditor from './ConfigurationEditor';
import {toolInvocationFailure, toolTerminal, useToolInvocation,} from './useToolInvocation';

const stateNames: Record<string, string> = {
  ACCEPTED: '已受理',
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '执行成功',
  FAILED: '执行失败',
  CANCELLED: '已取消',
  TIMED_OUT: '已超时',
  INTERRUPTED: '执行中断',
  UNKNOWN: '结果待核对',
};

/** 预览只读；确认后保存原命令再提交。状态、结果、停止都指向同一耐久调用。 */
export default function ToolRunPanel({
                                       context,
                                       bindings,
                                       selectedBindingId,
                                       onDirty,
                                     }: {
  context: BrowserContext;
  bindings: ToolBinding[];
  selectedBindingId?: string;
  onDirty: (dirty: boolean) => void;
}) {
  const execution = useToolInvocation(context);
  const [modal, modalContext] = Modal.useModal();
  const [bindingId, setBindingId] = useState(selectedBindingId ?? '');
  const [argumentsJson, setArgumentsJson] = useState('{}');
  const [timeoutSeconds, setTimeoutSeconds] = useState(30);
  const [schema, setSchema] = useState<ToolSchema | null>(null);
  const [preview, setPreview] = useState<{
    value: ToolInvocationPreview;
    requestJson: string;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [historyId, setHistoryId] = useState('');
  const active = useRef<AbortController | null>(null);
  const draftVersion = useRef(0);
  const currentBinding = bindings.find(
    (binding) => binding.bindingId === bindingId,
  );
  const currentBindingVersion = currentBinding?.version;
  const invalid = () => {
    draftVersion.current++;
    active.current?.abort();
    setPreviewing(false);
    setPreview(null);
    onDirty(true);
  };
  useEffect(() => {
    if (selectedBindingId) setBindingId(selectedBindingId);
  }, [selectedBindingId]);
  useEffect(() => {
    const controller = new AbortController();
    draftVersion.current++;
    active.current?.abort();
    setPreviewing(false);
    setPreview(null);
    setSchema(null);
    setError(null);
    if (bindingId) {
      const credential = currentAiCredential();
      void getToolBinding(context.scope, bindingId, {
        signal: controller.signal,
      })
        .then(({body}) =>
          getTool(context.scope, body.data.revision.settings.tool, {
            signal: controller.signal,
          }),
        )
        .then(({body}) => {
          if (
            !controller.signal.aborted &&
            currentAiCredential() === credential
          )
            setSchema(body.data.inputSchema);
        })
        .catch((failure) => {
          if (
            !controller.signal.aborted &&
            currentAiCredential() === credential
          )
            setError(toolInvocationFailure(failure));
        });
    }
    return () => controller.abort();
  }, [bindingId, currentBindingVersion, context.scope]);
  useEffect(
    () => () => {
      active.current?.abort();
      onDirty(false);
    },
    [onDirty],
  );
  useEffect(() => {
    if (execution.record) {
      setPreview(null);
      onDirty(false);
    }
  }, [execution.record, onDirty]);
  const inspect = async () => {
    if (previewing || execution.record || execution.blocked) return;
    const controller = new AbortController();
    active.current?.abort();
    active.current = controller;
    const version = draftVersion.current;
    const credential = currentAiCredential();
    setPreviewing(true);
    setError(null);
    setPreview(null);
    try {
      const requestJson = toolInvocationRequest(
        context.scope,
        bindingId,
        argumentsJson,
        timeoutSeconds,
      );
      const {body} = await previewToolInvocation(requestJson, {
        signal: controller.signal,
      });
      if (
        !controller.signal.aborted &&
        version === draftVersion.current &&
        currentAiCredential() === credential
      ) {
        if (
          body.data.billingMode !== 'FREE' ||
          body.data.approvalMode !== 'NONE' ||
          !/^[0-9a-f]{64}$/.test(body.data.planDigest)
        )
          throw new Error('工具超出当前支持范围，无法确认调用。');
        setPreview({value: body.data, requestJson});
        if (body.data.inputSchema) setSchema(body.data.inputSchema);
      }
    } catch (failure) {
      if (
        !controller.signal.aborted &&
        version === draftVersion.current &&
        currentAiCredential() === credential
      )
        setError(toolInvocationFailure(failure));
    } finally {
      if (active.current === controller) active.current = null;
      if (!controller.signal.aborted) setPreviewing(false);
    }
  };
  useEffect(() => {
    if (execution.error && !execution.record) setPreview(null);
  }, [execution.error, execution.record]);
  const locked = !!execution.record || execution.blocked;
  const knownState = execution.status && stateNames[execution.status.state];
  return (
    <Flex vertical gap={16} className="tool-run-panel">
      {modalContext}
      <Alert
        type="info"
        showIcon
        title="独立工具调用"
        description="当前支持已授权的本地免费只读工具。先预览有效版本、参数与配置，再确认执行；不会调用聊天模型。"
      />
      {execution.error && (
        <Alert type="warning" showIcon title={execution.error}/>
      )}
      {execution.record ? (
        <Card title="当前调用" size="small">
          <Flex vertical gap={12}>
            {execution.record.acceptedId ? (
              <>
                <Typography.Text copyable code>
                  {execution.record.acceptedId}
                </Typography.Text>
                <Space wrap>
                  <Tag
                    color={
                      execution.status?.state === 'SUCCEEDED' ? 'green' : 'blue'
                    }
                  >
                    {knownState ?? '正在读取状态'}
                  </Tag>
                  {execution.status && (
                    <Tag>
                      预算：
                      {execution.status.budgetState === 'NOT_RESERVED'
                        ? '未预留'
                        : execution.status.budgetState}
                    </Tag>
                  )}
                </Space>
                {execution.readError && (
                  <Alert
                    type="warning"
                    title={execution.readError}
                    description="查询失败不代表执行失败。请保留原调用编号，恢复权限或连接后重新读取。"
                  />
                )}
                {execution.cancelReceipt && (
                  <Alert type="info" title={execution.cancelReceipt}/>
                )}
                {execution.status?.error && (
                  <Alert
                    type={
                      execution.status.state === 'UNKNOWN' ? 'warning' : 'error'
                    }
                    title={
                      execution.status.state === 'UNKNOWN'
                        ? '结果尚不确定，请保留编号核对，勿盲目重新执行。'
                        : `执行错误：${execution.status.error.code}`
                    }
                    description={`阶段：${execution.status.error.phase}；副作用：${execution.status.error.sideEffect}；追踪：${execution.status.error.correlationId}`}
                  />
                )}
                <Space wrap>
                  <Button onClick={execution.refresh}>重新读取原调用</Button>
                  <Button
                    loading={execution.busy}
                    disabled={
                      execution.blocked || toolTerminal(execution.status)
                    }
                    onClick={execution.cancel}
                  >
                    {execution.record.cancelKey ? '确认原停止请求' : '请求停止'}
                  </Button>
                  {(execution.record.historyOnly ||
                    (toolTerminal(execution.status) &&
                      execution.status?.state !== 'UNKNOWN')) && (
                    <Button
                      disabled={execution.blocked}
                      onClick={() => execution.clear()}
                    >
                      {execution.record.historyOnly
                        ? '结束历史查询'
                        : '开始新的调用'}
                    </Button>
                  )}
                </Space>
                {execution.status?.state === 'UNKNOWN' &&
                  !execution.record.historyOnly && (
                    <Button
                      onClick={() =>
                        modal.confirm({
                          title: '本次调用的结果仍待核对',
                          content: `结束查看会保留原调用编号 ${execution.record?.acceptedId}。不会重发原操作；请勿根据不确定结果重复操作。新调用仍须重新填写、预览并确认。`,
                          okText: '保留编号并结束查看',
                          cancelText: '继续查看原调用',
                          onOk: () => {
                            execution.clear(true);
                            setArgumentsJson('{}');
                            setPreview(null);
                            onDirty(false);
                          },
                        })
                      }
                    >
                      结束本次查看
                    </Button>
                  )}
                {execution.result && (
                  <>
                    <Typography.Title level={5}>结构化结果</Typography.Title>
                    <Typography.Text>
                      工具结果：{execution.result.value.outcome} · 副作用：
                      {execution.result.value.sideEffect}
                    </Typography.Text>
                    <section aria-label="工具结构化结果">
                      <pre className="tool-json">
                        {execution.result.outputJson ?? '无结构化输出'}
                      </pre>
                    </section>
                    {!!(
                      execution.result.value.sources?.length ||
                      execution.result.value.artifacts?.length
                    ) && (
                      <Collapse
                        items={[
                          {
                            key: 'references',
                            label: '来源与产物引用',
                            children: (
                              <>
                                <Typography.Paragraph type="secondary">
                                  引用由服务端返回；访问实际资源仍须通过宿主授权入口。
                                </Typography.Paragraph>
                                <pre className="tool-json">
                                  {JSON.stringify(
                                    {
                                      sources: execution.result.value.sources,
                                      artifacts:
                                      execution.result.value.artifacts,
                                    },
                                    null,
                                    2,
                                  )}
                                </pre>
                              </>
                            ),
                          },
                        ]}
                      />
                    )}
                  </>
                )}
              </>
            ) : (
              <>
                <Alert
                  type="warning"
                  title="提交结果尚待确认"
                  description="请使用原输入和原幂等键确认。刷新、切换空间或网络中断不会撤销服务器调用。"
                />
                <Button
                  type="primary"
                  loading={execution.busy}
                  disabled={execution.blocked}
                  onClick={execution.retry}
                >
                  确认原调用
                </Button>
              </>
            )}
          </Flex>
        </Card>
      ) : (
        <>
          {error && <Alert type="warning" showIcon title={error}/>}
          <Form layout="vertical">
            <Form.Item label="工具配置">
              <Select
                aria-label="调用工具配置"
                value={bindingId || undefined}
                placeholder="选择本人已启用的配置"
                disabled={locked}
                options={bindings
                  .filter((binding) => binding.enabled)
                  .map((binding) => ({
                    value: binding.bindingId,
                    label: binding.name,
                  }))}
                onChange={(value) => {
                  invalid();
                  setBindingId(value);
                  setArgumentsJson('{}');
                }}
              />
            </Form.Item>
            {schema && (
              <ConfigurationEditor
                purpose="arguments"
                schema={schema}
                value={argumentsJson}
                onChange={(value) => {
                  invalid();
                  setArgumentsJson(value);
                }}
              />
            )}
            {!schema && bindingId && (
              <Form.Item label="工具参数 JSON">
                <Input.TextArea
                  aria-label="完整参数 JSON"
                  rows={6}
                  value={argumentsJson}
                  onChange={(event) => {
                    invalid();
                    setArgumentsJson(event.target.value);
                  }}
                />
              </Form.Item>
            )}
            <Form.Item label="总期限（秒，包含受理与排队）">
              <InputNumber
                aria-label="调用总期限"
                min={1}
                max={3600}
                precision={0}
                value={timeoutSeconds}
                onChange={(value) => {
                  invalid();
                  setTimeoutSeconds(value ?? 30);
                }}
              />
            </Form.Item>
            <Button
              loading={previewing}
              disabled={
                locked || !bindingId || currentBinding?.enabled === false
              }
              onClick={() => void inspect()}
            >
              预览调用
            </Button>
          </Form>
          {preview && (
            <Card size="small" title="确认本次有效方案">
              <Flex vertical gap={12}>
                <Descriptions
                  size="small"
                  column={1}
                  items={[
                    {
                      key: 'tool',
                      label: '实际工具',
                      children: `${preview.value.title} · ${preview.value.tool.id} / ${preview.value.tool.version}`,
                    },
                    {
                      key: 'revision',
                      label: '配置修订',
                      children: preview.value.revision.version,
                    },
                    {
                      key: 'timeout',
                      label: '执行上限',
                      children: `${preview.value.timeoutMillis / 1000} 秒；最多 ${preview.value.maxAttempts} 次尝试；输出 ${preview.value.maxOutputBytes} 字节`,
                    },
                    {
                      key: 'cost',
                      label: '费用',
                      children: preview.value.costDescription,
                    },
                  ]}
                />
                <Typography.Text strong>实际参数</Typography.Text>
                <pre className="tool-json">{preview.value.argumentsJson}</pre>
                <Collapse
                  items={[
                    {
                      key: 'configuration',
                      label: '有效配置',
                      children: (
                        <pre className="tool-json">
                          {preview.value.configurationJson}
                        </pre>
                      ),
                    },
                  ]}
                />
                <Typography.Text type="secondary">
                  配置或发布变化时，服务端会拒绝过期方案，要求重新预览。尝试上限不代表工具会被自动重试。
                </Typography.Text>
                <Button
                  type="primary"
                  loading={execution.busy}
                  disabled={locked}
                  onClick={() =>
                    execution.start(
                      confirmedToolRequest(
                        preview.requestJson,
                        preview.value.planDigest,
                      ),
                    )
                  }
                >
                  确认并执行工具
                </Button>
              </Flex>
            </Card>
          )}
          <Card size="small" title="恢复已有调用">
            {!!execution.history.length && (
              <Form.Item label="最近调用（当前浏览器会话）">
                <Select
                  aria-label="最近工具调用"
                  placeholder="选择原调用编号"
                  value={historyId || undefined}
                  options={execution.history.map((id) => ({
                    value: id,
                    label: id,
                  }))}
                  onChange={setHistoryId}
                />
              </Form.Item>
            )}
            <Space.Compact block>
              <Input
                aria-label="原工具调用编号"
                value={historyId}
                maxLength={256}
                placeholder="粘贴本人在当前空间的调用编号"
                onChange={(event) => setHistoryId(event.target.value)}
              />
              <Button
                disabled={locked || !historyId.trim()}
                onClick={() => execution.open(historyId)}
              >
                只读查询
              </Button>
            </Space.Compact>
          </Card>
        </>
      )}
    </Flex>
  );
}
