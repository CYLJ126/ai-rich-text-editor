import {
  CopyOutlined,
  EditOutlined,
  EyeOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {Button, Form, InputNumber, message, Modal, Segmented, Select, Space, Tag, Typography,} from 'antd';
import dayjs from 'dayjs';
import React, {useEffect, useMemo, useRef, useState} from 'react';
import {JsonEditor} from '@/components';
import type {JsonSchemaFormRef} from '@/components/AITool';
import {JsonSchemaForm} from '@/components/AITool';
import {isActiveWorkflowRun, normalizeToolError, WORKFLOW_RUN_PRESENTATION,} from '@/features/ai-tool';
import {
  getWorkflowVersion,
  listWorkflowRuns,
  listWorkflows,
  listWorkflowVersions,
  startWorkflowRun,
} from '@/services/ant-design-pro/ai.tool.workflow';
import type {
  JsonObject,
  WorkflowRunView,
  WorkflowStartRequest,
  WorkflowSummaryView,
  WorkflowVersionView,
} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';
import WorkflowEditorDrawer from './WorkflowEditorDrawer';
import WorkflowRunDrawer from './WorkflowRunDrawer';

type EditorState = {
  open: boolean;
  initial?: WorkflowVersionView;
  clonePublished?: boolean;
};

export default function WorkflowManagementPage() {
  const workflowAction = useRef<ActionType>(null);
  const runAction = useRef<ActionType>(null);
  const [view, setView] = useState<'definitions' | 'runs'>('definitions');
  const [editor, setEditor] = useState<EditorState>({open: false});
  const [runTarget, setRunTarget] = useState<WorkflowSummaryView>();
  const [versionTarget, setVersionTarget] = useState<WorkflowSummaryView>();
  const [selectedRunId, setSelectedRunId] = useState<string>();
  const [hasActiveRuns, setHasActiveRuns] = useState(false);

  useEffect(() => {
    if (!hasActiveRuns) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') runAction.current?.reload();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [hasActiveRuns]);

  const openVersion = async (
    workflowId: string,
    version: string,
    clonePublished = false,
  ) => {
    try {
      const detail = await getWorkflowVersion(workflowId, version);
      if (!detail) throw new Error('工作流版本不存在或无权访问');
      setEditor({open: true, initial: detail, clonePublished});
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    }
  };

  const openLatest = async (record: WorkflowSummaryView) => {
    if (record.latestVersion) {
      await openVersion(record.workflowId, record.latestVersion);
      return;
    }
    try {
      const versions = await listWorkflowVersions(record.workflowId);
      const draft = versions.find((item) => item.lifecycleState === 'draft');
      if (!draft) throw new Error('工作流没有可编辑版本');
      setEditor({open: true, initial: draft});
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    }
  };

  const workflowColumns: ProColumns<WorkflowSummaryView>[] = [
    {
      title: '工作流',
      dataIndex: 'keyword',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{record.name}</Typography.Text>
          <Typography.Text type="secondary" copyable>
            {record.workflowId}
          </Typography.Text>
        </Space>
      ),
    },
    {title: '说明', dataIndex: 'description', search: false, ellipsis: true},
    {
      title: '最新版本',
      dataIndex: 'latestVersion',
      search: false,
      width: 110,
      render: (_, record) => <Tag>{record.latestVersion}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      valueEnum: {
        draft: {text: '草稿'},
        published: {text: '已发布'},
        deprecated: {text: '已废弃'},
        disabled: {text: '已禁用'},
      },
      render: (_, record) => (
        <Tag
          color={record.lifecycleState === 'published' ? 'success' : 'default'}
        >
          {record.lifecycleState}
        </Tag>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      search: false,
      width: 170,
      render: (_, record) =>
        dayjs(record.updateTime).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 320,
      render: (_, record) => [
        <Button
          key="open"
          type="link"
          icon={
            record.lifecycleState === 'draft' ? (
              <EditOutlined/>
            ) : (
              <EyeOutlined/>
            )
          }
          onClick={() => openLatest(record)}
        >
          {record.lifecycleState === 'draft' ? '编辑' : '查看'}
        </Button>,
        record.lifecycleState === 'published' && record.latestVersion && (
          <Button
            key="clone"
            type="link"
            icon={<CopyOutlined/>}
            onClick={() =>
              openVersion(record.workflowId, record.latestVersion || '', true)
            }
          >
            新版本
          </Button>
        ),
        <Button
          key="versions"
          type="link"
          onClick={() => setVersionTarget(record)}
        >
          版本
        </Button>,
        record.lifecycleState === 'published' && (
          <Button
            key="run"
            type="link"
            icon={<PlayCircleOutlined/>}
            onClick={() => setRunTarget(record)}
          >
            运行
          </Button>
        ),
      ],
    },
  ];

  const runColumns: ProColumns<WorkflowRunView>[] = [
    {
      title: '状态',
      dataIndex: 'status',
      valueType: 'select',
      width: 125,
      valueEnum: Object.fromEntries(
        Object.entries(WORKFLOW_RUN_PRESENTATION).map(([key, item]) => [
          key,
          {text: item.label},
        ]),
      ),
      render: (_, record) => {
        const item = WORKFLOW_RUN_PRESENTATION[record.status];
        return <Tag color={item.color}>{item.label}</Tag>;
      },
    },
    {
      title: '工作流',
      dataIndex: 'workflowId',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{record.workflowId}</Typography.Text>
          <Typography.Text type="secondary">
            版本 {record.workflowVersion}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '运行 ID',
      dataIndex: 'runId',
      search: false,
      ellipsis: true,
      render: (value) => <Typography.Text copyable>{value}</Typography.Text>,
    },
    {
      title: '步骤',
      search: false,
      width: 90,
      render: (_, record) => `${record.currentSteps}/${record.maximumSteps}`,
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      search: false,
      width: 170,
      render: (_, record) =>
        dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 90,
      render: (_, record) => (
        <Button type="link" onClick={() => setSelectedRunId(record.runId)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <ToolManagementPage
      activeKey="workflows"
      title="工作流编排与运行"
      subTitle="以不可变版本保存、校验和发布 DSL，所有工具节点通过统一 ToolGateway 执行"
      extra={
        <Space>
          <Segmented
            value={view}
            options={[
              {label: '工作流定义', value: 'definitions'},
              {label: '运行记录', value: 'runs'},
            ]}
            onChange={(value) => setView(value as 'definitions' | 'runs')}
          />
          {view === 'definitions' && (
            <Button
              type="primary"
              icon={<PlusOutlined/>}
              onClick={() => setEditor({open: true})}
            >
              新建工作流
            </Button>
          )}
        </Space>
      }
    >
      {view === 'definitions' ? (
        <ProTable<WorkflowSummaryView>
          actionRef={workflowAction}
          rowKey="workflowId"
          columns={workflowColumns}
          request={async (params) => {
            try {
              const page = await listWorkflows({
                keyword: params.keyword,
                status: params.status,
                current: params.current,
                pageSize: params.pageSize,
              });
              return {data: page.records, total: page.total, success: true};
            } catch (error) {
              message.error(normalizeToolError(error).message).then();
              return {data: [], total: 0, success: false};
            }
          }}
          pagination={{defaultPageSize: 20, showSizeChanger: true}}
          toolBarRender={() => [
            <Button
              key="reload"
              icon={<ReloadOutlined/>}
              onClick={() => workflowAction.current?.reload()}
            >
              刷新
            </Button>,
          ]}
        />
      ) : (
        <ProTable<WorkflowRunView>
          actionRef={runAction}
          rowKey="runId"
          columns={runColumns}
          request={async (params) => {
            try {
              const page = await listWorkflowRuns({
                workflowId: params.workflowId,
                status: params.status,
                current: params.current,
                pageSize: params.pageSize,
              });
              setHasActiveRuns(
                page.records.some((run) => isActiveWorkflowRun(run.status)),
              );
              return {data: page.records, total: page.total, success: true};
            } catch (error) {
              message.error(normalizeToolError(error).message).then();
              return {data: [], total: 0, success: false};
            }
          }}
          pagination={{defaultPageSize: 20, showSizeChanger: true}}
          toolBarRender={() => [
            <Button
              key="reload"
              icon={<ReloadOutlined/>}
              onClick={() => runAction.current?.reload()}
            >
              刷新
            </Button>,
          ]}
        />
      )}
      <WorkflowEditorDrawer
        {...editor}
        onClose={() => setEditor({open: false})}
        onSaved={() => workflowAction.current?.reload()}
      />
      <RunModal
        target={runTarget}
        onClose={() => setRunTarget(undefined)}
        onStarted={(runId) => {
          setRunTarget(undefined);
          setView('runs');
          setSelectedRunId(runId);
          runAction.current?.reload();
        }}
      />
      <VersionModal
        target={versionTarget}
        onClose={() => setVersionTarget(undefined)}
        onOpen={(value, clonePublished) => {
          setVersionTarget(undefined);
          setEditor({open: true, initial: value, clonePublished});
        }}
      />
      <WorkflowRunDrawer
        runId={selectedRunId}
        open={Boolean(selectedRunId)}
        onClose={() => setSelectedRunId(undefined)}
        onChanged={() => runAction.current?.reload()}
      />
    </ToolManagementPage>
  );
}

function VersionModal({
                        target,
                        onClose,
                        onOpen,
                      }: {
  target?: WorkflowSummaryView;
  onClose: () => void;
  onOpen: (value: WorkflowVersionView, clonePublished: boolean) => void;
}) {
  const [versions, setVersions] = useState<WorkflowVersionView[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!target) return;
    setLoading(true);
    listWorkflowVersions(target.workflowId)
      .then(setVersions)
      .catch((error) => message.error(normalizeToolError(error).message).then())
      .finally(() => setLoading(false));
  }, [target]);

  return (
    <Modal
      title={`${target?.name || ''} · 版本管理`}
      width={760}
      open={Boolean(target)}
      onCancel={onClose}
      footer={null}
      loading={loading}
    >
      <ProTable<WorkflowVersionView>
        rowKey="version"
        search={false}
        options={false}
        pagination={false}
        dataSource={versions}
        columns={[
          {title: '版本', dataIndex: 'version'},
          {
            title: '状态',
            dataIndex: 'lifecycleState',
            render: (_, record) => (
              <Tag
                color={
                  record.lifecycleState === 'published' ? 'success' : 'default'
                }
              >
                {record.lifecycleState}
              </Tag>
            ),
          },
          {
            title: '更新时间',
            dataIndex: 'updateTime',
            render: (_, record) =>
              dayjs(record.updateTime).format('YYYY-MM-DD HH:mm:ss'),
          },
          {
            title: '操作',
            valueType: 'option',
            render: (_, record) => [
              <Button
                key="open"
                type="link"
                onClick={() => onOpen(record, false)}
              >
                {record.lifecycleState === 'draft' ? '编辑' : '查看'}
              </Button>,
              record.lifecycleState === 'published' && (
                <Button
                  key="clone"
                  type="link"
                  onClick={() => onOpen(record, true)}
                >
                  创建新版本
                </Button>
              ),
            ],
          },
        ]}
      />
    </Modal>
  );
}

function RunModal({
                    target,
                    onClose,
                    onStarted,
                  }: {
  target?: WorkflowSummaryView;
  onClose: () => void;
  onStarted: (runId: string) => void;
}) {
  const [form] = Form.useForm<WorkflowStartRequest>();
  const inputFormRef = useRef<JsonSchemaFormRef>(null);
  const [versions, setVersions] = useState<WorkflowVersionView[]>([]);
  const [version, setVersion] = useState<WorkflowVersionView>();
  const [inputs, setInputs] = useState<JsonObject>({});
  const [variables, setVariables] = useState<JsonObject>({});
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!target) return;
    listWorkflowVersions(target.workflowId)
      .then((values) => {
        const published = values.filter(
          (item) => item.lifecycleState === 'published',
        );
        setVersions(published);
        const selected =
          published.find((item) => item.version === target.latestVersion) ||
          published[0];
        setVersion(selected);
        form.setFieldsValue({
          workflowId: target.workflowId,
          version: selected?.version,
          maximumSteps: selected?.executionPolicy.maximumSteps,
        });
      })
      .catch((error) =>
        message.error(normalizeToolError(error).message).then(),
      );
  }, [form, target]);

  const schema = useMemo(() => {
    if (!version) return {};
    try {
      return JSON.parse(version.inputSchema.schema) as JsonObject;
    } catch {
      return {};
    }
  }, [version]);

  const submit = async () => {
    setLoading(true);
    try {
      const values = await form.validateFields();
      const validatedInputs =
        (await inputFormRef.current?.validate()) || inputs;
      const result = await startWorkflowRun({
        ...values,
        inputs: validatedInputs,
        variables,
      });
      if (result.resumeToken) {
        Modal.info({
          title: '请安全保存恢复令牌',
          content: (
            <Typography.Text copyable>{result.resumeToken}</Typography.Text>
          ),
        });
      }
      message.success('工作流运行已启动').then();
      onStarted(result.run.runId);
    } catch (error) {
      if ((error as { errorFields?: unknown }).errorFields) return;
      message.error(normalizeToolError(error).message).then();
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="运行已发布工作流"
      width={760}
      open={Boolean(target)}
      onCancel={onClose}
      onOk={submit}
      confirmLoading={loading}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item name="workflowId" label="工作流 ID">
          <Select
            disabled
            options={
              target
                ? [{value: target.workflowId, label: target.workflowId}]
                : []
            }
          />
        </Form.Item>
        <Form.Item
          name="version"
          label="已发布版本"
          rules={[{required: true}]}
        >
          <Select
            options={versions.map((item) => ({
              value: item.version,
              label: item.version,
            }))}
            onChange={(value) => {
              const selected = versions.find((item) => item.version === value);
              setVersion(selected);
              setInputs({});
              form.setFieldValue(
                'maximumSteps',
                selected?.executionPolicy.maximumSteps,
              );
            }}
          />
        </Form.Item>
        <Form.Item
          name="maximumSteps"
          label="本次最大步骤数"
          rules={[{required: true}]}
        >
          <InputNumber min={1} max={10000}/>
        </Form.Item>
      </Form>
      <Typography.Title level={5}>运行输入</Typography.Title>
      <JsonSchemaForm
        key={version?.version}
        ref={inputFormRef}
        schema={schema}
        value={inputs}
        onChange={setInputs}
        columns={2}
      />
      <Typography.Title level={5} style={{marginTop: 16}}>
        初始变量（可选）
      </Typography.Title>
      <JsonEditor
        value={variables}
        height={180}
        onChange={(value) => {
          if (value && !Array.isArray(value)) setVariables(value as JsonObject);
        }}
      />
    </Modal>
  );
}
