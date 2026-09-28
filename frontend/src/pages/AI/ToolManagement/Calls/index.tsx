import {PlayCircleOutlined, ReloadOutlined} from '@ant-design/icons';
import {useAccess, useNavigate} from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  message,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import React, {useCallback, useEffect, useMemo, useRef, useState,} from 'react';
import type {JsonSchemaFormRef} from '@/components/AITool';
import {JsonSchemaForm, ToolErrorAlert, ToolResultView, ToolStatusTag,} from '@/components/AITool';
import {createIdempotencyKey, normalizeToolError, toolErrorFromPayload, toolReferenceLabel,} from '@/features/ai-tool';
import {getToolVersionDetail, invokeTool, listToolBindings, resumeTool,} from '@/services/ant-design-pro/ai.tool';
import type {
  RestToolInvokeRequest,
  ToolBindingView,
  ToolExecutionMode,
  ToolResult,
  ToolVersionDetailView,
} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';

interface InvocationOptions {
  bindingId: string;
  executionMode: ToolExecutionMode;
  timeout?: string;
  maxRetries?: number;
  idempotencyKey: string;
}

const executionModes: Array<{ label: string; value: ToolExecutionMode }> = [
  {label: '同步执行（BLOCKING）', value: 'blocking'},
  {label: '非阻塞执行（NON_BLOCKING）', value: 'non-blocking'},
  {label: '后台任务（DEFERRED）', value: 'deferred'},
];

export default function ToolInvocationPage() {
  const access = useAccess();
  const navigate = useNavigate();
  const [form] = Form.useForm<InvocationOptions>();
  const schemaRef = useRef<JsonSchemaFormRef>(null);
  const [scopeDraft, setScopeDraft] = useState('');
  const [workspaceId, setWorkspaceId] = useState<string>();
  const [bindings, setBindings] = useState<ToolBindingView[]>([]);
  const [bindingsLoading, setBindingsLoading] = useState(false);
  const [detail, setDetail] = useState<ToolVersionDetailView>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [invoking, setInvoking] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [result, setResult] = useState<ToolResult>();
  const [error, setError] = useState<unknown>();
  const bindingId = Form.useWatch('bindingId', form);
  const selectedBinding = useMemo(
    () => bindings.find((binding) => binding.bindingId === bindingId),
    [bindingId, bindings],
  );

  const loadBindings = useCallback(
    async (scope?: string) => {
      setBindingsLoading(true);
      setError(undefined);
      try {
        const items = await listToolBindings(scope);
        const callable = items.filter((item) => item.enabled && item.available);
        setBindings(callable);
        const current = form.getFieldValue('bindingId');
        form.setFieldValue(
          'bindingId',
          callable.some((item) => item.bindingId === current)
            ? current
            : callable[0]?.bindingId,
        );
        if (!callable.length) setDetail(undefined);
      } catch (nextError) {
        setError(nextError);
        setBindings([]);
        setDetail(undefined);
      } finally {
        setBindingsLoading(false);
      }
    },
    [form],
  );

  useEffect(() => {
    form.setFieldsValue({
      executionMode: 'deferred',
      timeout: 'PT5M',
      maxRetries: 0,
      idempotencyKey: createIdempotencyKey(),
    });
    loadBindings().then();
  }, [form, loadBindings]);

  useEffect(() => {
    if (!selectedBinding) {
      setDetail(undefined);
      return;
    }
    let active = true;
    setDetailLoading(true);
    setError(undefined);
    getToolVersionDetail(selectedBinding.tool)
      .then((nextDetail) => {
        if (!active) return;
        if (!nextDetail) throw new Error('工具版本不存在或已不可用');
        setDetail(nextDetail);
        schemaRef.current?.reset();
        const policy = {
          ...nextDetail.defaultPolicy,
          ...(selectedBinding.policyOverride || {}),
        };
        form.setFieldsValue({
          executionMode: readExecutionMode(policy.executionMode) || 'deferred',
          timeout: typeof policy.timeout === 'string' ? policy.timeout : 'PT5M',
          maxRetries:
            typeof policy.maxRetries === 'number' ? policy.maxRetries : 0,
        });
      })
      .catch((nextError) => active && setError(nextError))
      .finally(() => active && setDetailLoading(false));
    return () => {
      active = false;
    };
  }, [form, selectedBinding]);

  const applyScope = () => {
    const nextWorkspaceId = scopeDraft.trim() || undefined;
    setWorkspaceId(nextWorkspaceId);
    setResult(undefined);
    loadBindings(nextWorkspaceId).then();
  };

  const invoke = async () => {
    if (!selectedBinding || !detail) return;
    setInvoking(true);
    setError(undefined);
    setResult(undefined);
    try {
      const [options, argumentsValue] = await Promise.all([
        form.validateFields(),
        schemaRef.current?.validate() || Promise.resolve({}),
      ]);
      const request: RestToolInvokeRequest = {
        tool: selectedBinding.tool,
        bindingId: selectedBinding.bindingId,
        workspaceId: selectedBinding.workspaceId,
        arguments: argumentsValue,
        executionMode: options.executionMode,
        timeout: options.timeout?.trim() || undefined,
        maxRetries: options.maxRetries,
        idempotencyKey: options.idempotencyKey.trim(),
      };
      setResult(await invokeTool(request));
      message.success('调用请求已提交').then();
    } catch (nextError) {
      setError(nextError);
    } finally {
      setInvoking(false);
    }
  };

  const resume = async (resumeToken: string) => {
    setResuming(true);
    setError(undefined);
    try {
      const nextResult = await resumeTool(resumeToken);
      setResult(nextResult);
      if (
        nextResult.status === 'failed' ||
        nextResult.status === 'denied' ||
        nextResult.status === 'cancelled' ||
        nextResult.status === 'timed-out'
      ) {
        throw toolErrorFromPayload(nextResult.error);
      }
      if (nextResult.status !== 'succeeded') {
        throw new Error('恢复请求未被接受');
      }
      message.success('恢复请求已提交').then();
    } catch (nextError) {
      setError(nextError);
    } finally {
      setResuming(false);
    }
  };

  const supportedModes = supportedExecutionModes(detail);

  return (
    <ToolManagementPage
      activeKey="calls"
      title="工具调用控制台"
      subTitle="所有请求通过统一 ToolGateway，复用绑定校验、Guardrail、审批、限流、追踪和持久化管道"
    >
      {!access.canInvokeAiTools && (
        <Alert
          type="warning"
          showIcon
          message="你只有查看权限，不能发起或恢复工具调用"
          style={{marginBottom: 16}}
        />
      )}
      <Card size="small" style={{marginBottom: 16}}>
        <Space.Compact style={{width: '100%'}}>
          <Input
            value={scopeDraft}
            onChange={(event) => setScopeDraft(event.target.value)}
            onPressEnter={applyScope}
            placeholder="工作空间 ID；留空使用个人作用域"
            allowClear
          />
          <Button loading={bindingsLoading} onClick={applyScope}>
            加载绑定
          </Button>
        </Space.Compact>
        <Typography.Text type="secondary">
          当前作用域：{workspaceId || '个人'}
          。仅展示已启用、已发布且当前可用的固定版本绑定。
        </Typography.Text>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          <Card loading={detailLoading} title="调用请求" bordered={false}>
            <Form<InvocationOptions>
              form={form}
              layout="vertical"
              disabled={!access.canInvokeAiTools || invoking}
            >
              <Form.Item
                name="bindingId"
                label="工具绑定"
                rules={[{required: true, message: '请选择可用工具绑定'}]}
              >
                <Select
                  loading={bindingsLoading}
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择固定工具版本"
                  options={bindings.map((binding) => ({
                    value: binding.bindingId,
                    label: `${toolReferenceLabel(binding.tool)} · ${binding.bindingId}`,
                  }))}
                />
              </Form.Item>
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="executionMode"
                    label="执行模式"
                    rules={[{required: true}]}
                  >
                    <Select
                      options={executionModes.map((option) => ({
                        ...option,
                        disabled:
                          supportedModes.length > 0 &&
                          !supportedModes.includes(option.value),
                      }))}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="timeout"
                    label="超时"
                    tooltip="ISO-8601 Duration，例如 PT30S、PT5M"
                    rules={[
                      {
                        pattern: /^P(?!$).+$/i,
                        message: '请输入 ISO-8601 Duration，例如 PT30S',
                      },
                    ]}
                  >
                    <Input placeholder="PT5M"/>
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item name="maxRetries" label="最大重试次数">
                    <InputNumber
                      min={0}
                      precision={0}
                      style={{width: '100%'}}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="idempotencyKey"
                    label="幂等键"
                    rules={[
                      {
                        required: true,
                        whitespace: true,
                        message: '幂等键不能为空',
                      },
                    ]}
                  >
                    <Input
                      addonAfter={
                        <Button
                          type="text"
                          size="small"
                          aria-label="生成新幂等键"
                          icon={<ReloadOutlined/>}
                          onClick={() =>
                            form.setFieldValue(
                              'idempotencyKey',
                              createIdempotencyKey(),
                            )
                          }
                        />
                      }
                    />
                  </Form.Item>
                </Col>
              </Row>
            </Form>

            <Divider titlePlacement="start">工具参数</Divider>
            {detail ? (
              <JsonSchemaForm
                key={bindingId}
                ref={schemaRef}
                schema={detail.inputSchema}
                disabled={!access.canInvokeAiTools || invoking}
                columns={2}
              />
            ) : (
              <Alert type="info" showIcon message="请选择工具绑定后填写参数"/>
            )}
            <Button
              type="primary"
              icon={<PlayCircleOutlined/>}
              loading={invoking}
              disabled={!access.canInvokeAiTools || !detail}
              onClick={invoke}
            >
              发起调用
            </Button>
          </Card>
        </Col>

        <Col xs={24} xl={10}>
          <Space direction="vertical" size="middle" style={{width: '100%'}}>
            {detail && (
              <Card size="small" title="已解析工具">
                <Descriptions size="small" column={1}>
                  <Descriptions.Item label="固定版本">
                    {toolReferenceLabel(detail.reference)}
                  </Descriptions.Item>
                  <Descriptions.Item label="生命周期">
                    <ToolStatusTag status={detail.lifecycleState}/>
                  </Descriptions.Item>
                  <Descriptions.Item label="支持模式">
                    <Space wrap>
                      {supportedModes.map((mode) => (
                        <ToolStatusTag key={mode} status={mode}/>
                      ))}
                    </Space>
                  </Descriptions.Item>
                  <Descriptions.Item label="风险级别">
                    {typeof detail.riskProfile.level === 'string' ? (
                      <Tag>{detail.riskProfile.level}</Tag>
                    ) : (
                      '-'
                    )}
                  </Descriptions.Item>
                  <Descriptions.Item label="描述">
                    {detail.description}
                  </Descriptions.Item>
                </Descriptions>
              </Card>
            )}
            {Boolean(error) && (
              <ToolErrorAlert error={normalizeToolError(error)} showDetails/>
            )}
            <Card title="执行结果" loading={invoking}>
              <ToolResultView
                result={result}
                loading={invoking}
                resuming={resuming}
                onResume={access.canInvokeAiTools ? resume : undefined}
                onOpenTask={(taskId) =>
                  navigate(
                    `/AI/ToolManagement/Tasks?taskId=${encodeURIComponent(taskId)}`,
                  )
                }
              />
            </Card>
          </Space>
        </Col>
      </Row>
    </ToolManagementPage>
  );
}

function readExecutionMode(value: unknown): ToolExecutionMode | undefined {
  return value === 'blocking' ||
  value === 'non-blocking' ||
  value === 'deferred'
    ? value
    : undefined;
}

function supportedExecutionModes(
  detail?: ToolVersionDetailView,
): ToolExecutionMode[] {
  const source = detail?.capabilities.executionModes;
  return Array.isArray(source)
    ? source
      .map(readExecutionMode)
      .filter((value): value is ToolExecutionMode => Boolean(value))
    : [];
}
