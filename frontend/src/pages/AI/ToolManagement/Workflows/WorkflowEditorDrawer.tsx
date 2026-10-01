import {CheckCircleOutlined, CloudUploadOutlined, PlusOutlined, SaveOutlined,} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Drawer,
  Form,
  Input,
  InputNumber,
  List,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import React, {useEffect, useMemo, useState} from 'react';
import {JsonEditor} from '@/components';
import {
  createWorkflowDraft,
  createWorkflowId,
  createWorkflowNode,
  nodeTypeLabel,
  normalizeToolError,
} from '@/features/ai-tool';
import {listToolCatalog, listToolVersions,} from '@/services/ant-design-pro/ai.tool';
import {publishWorkflowVersion, saveWorkflowDraft, validateWorkflow,} from '@/services/ant-design-pro/ai.tool.workflow';
import type {
  JsonObject,
  ToolCatalogItem,
  ToolReference,
  WorkflowDraftRequest,
  WorkflowEdge,
  WorkflowNode,
  WorkflowValidationIssue,
  WorkflowVersionView,
} from '@/types/ai.tool.type';
import WorkflowCanvas from './WorkflowCanvas';

interface WorkflowEditorDrawerProps {
  open: boolean;
  initial?: WorkflowVersionView;
  clonePublished?: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export default function WorkflowEditorDrawer({
                                               open,
                                               initial,
                                               clonePublished,
                                               onClose,
                                               onSaved,
                                             }: WorkflowEditorDrawerProps) {
  const [draft, setDraft] = useState<WorkflowDraftRequest>(createWorkflowDraft);
  const [rowVersion, setRowVersion] = useState<number>();
  const [selectedNodeId, setSelectedNodeId] = useState<string>();
  const [activeTab, setActiveTab] = useState('designer');
  const [issues, setIssues] = useState<WorkflowValidationIssue[]>([]);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [catalog, setCatalog] = useState<ToolCatalogItem[]>([]);
  const [toolVersions, setToolVersions] = useState<Record<string, string[]>>(
    {},
  );
  const readOnly = Boolean(
    initial && initial.lifecycleState !== 'draft' && !clonePublished,
  );

  useEffect(() => {
    if (!open) return;
    setActiveTab('designer');
    if (initial) {
      setDraft({
        workflowId: initial.workflowId,
        version: clonePublished
          ? nextVersion(initial.version)
          : initial.version,
        name: clonePublished ? `${initial.name}（新版本）` : initial.name,
        description: initial.description,
        inputSchema: initial.inputSchema,
        outputSchema: initial.outputSchema,
        nodes: initial.nodes,
        edges: initial.edges,
        tags: initial.tags,
        executionPolicy: initial.executionPolicy,
        expectedRowVersion: clonePublished ? undefined : initial.rowVersion,
      });
      setRowVersion(clonePublished ? undefined : initial.rowVersion);
      setSelectedNodeId(initial.nodes[0]?.nodeId);
    } else {
      const value = createWorkflowDraft();
      setDraft(value);
      setRowVersion(undefined);
      setSelectedNodeId(value.nodes[0]?.nodeId);
    }
    setIssues([]);
    listToolCatalog({lifecycleState: 'published', current: 1, pageSize: 200})
      .then((page) => setCatalog(page.records))
      .catch(() => setCatalog([]));
  }, [clonePublished, initial, open]);

  const selectedNode = useMemo(
    () => draft.nodes.find((node) => node.nodeId === selectedNodeId),
    [draft.nodes, selectedNodeId],
  );

  useEffect(() => {
    const reference = selectedNode?.tool;
    if (!reference) return;
    const key = `${reference.namespace}/${reference.name}`;
    if (toolVersions[key]) return;
    listToolVersions(reference.namespace, reference.name)
      .then((versions) =>
        setToolVersions((current) => ({
          ...current,
          [key]: versions
            .filter((item) => item.lifecycleState === 'published')
            .map((item) => item.reference.version),
        })),
      )
      .catch(() => undefined);
  }, [selectedNode?.tool, toolVersions]);

  const updateDraft = <K extends keyof WorkflowDraftRequest>(
    key: K,
    value: WorkflowDraftRequest[K],
  ) => setDraft((current) => ({...current, [key]: value}));

  const updateNode = (next: WorkflowNode) =>
    updateDraft(
      'nodes',
      draft.nodes.map((node) => (node.nodeId === next.nodeId ? next : node)),
    );

  const selectTool = async (key: string) => {
    if (!selectedNode) return;
    const [namespace, name] = key.split('/');
    const versions =
      toolVersions[key] ||
      (await listToolVersions(namespace, name))
        .filter((item) => item.lifecycleState === 'published')
        .map((item) => item.reference.version);
    setToolVersions((current) => ({...current, [key]: versions}));
    updateNode({
      ...selectedNode,
      tool: {namespace, name, version: versions[0] || ''},
    });
  };

  const save = async () => {
    setSaving(true);
    try {
      const saved = await saveWorkflowDraft({
        ...draft,
        expectedRowVersion: rowVersion,
      });
      setDraft({...draft, expectedRowVersion: saved.rowVersion});
      setRowVersion(saved.rowVersion);
      onSaved();
      message.success('工作流草稿已保存').then();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setSaving(false);
    }
  };

  const validate = async () => {
    setValidating(true);
    try {
      const result = await validateWorkflow({
        ...draft,
        expectedRowVersion: rowVersion,
      });
      setIssues(result.issues);
      if (result.valid) message.success('工作流校验通过').then();
      else message.warning('校验发现阻断问题，请按提示修正').then();
      return result.valid;
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
      return false;
    } finally {
      setValidating(false);
    }
  };

  const publish = async () => {
    if (rowVersion === undefined) {
      message.warning('请先保存草稿').then();
      return;
    }
    setPublishing(true);
    try {
      if (!(await validate())) return;
      await publishWorkflowVersion(draft.workflowId, draft.version, rowVersion);
      message.success('工作流已发布，工具版本和执行计划已固定').then();
      onSaved();
      onClose();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setPublishing(false);
    }
  };

  return (
    <Drawer
      title={
        readOnly
          ? '查看已发布工作流'
          : initial
            ? '编辑工作流版本'
            : '新建工作流'
      }
      size="min(1500px, 96vw)"
      open={open}
      onClose={onClose}
      destroyOnHidden
      styles={{
        body: {
          display: 'flex',
          flexDirection: 'column',
          minHeight: 0,
          overflow: 'hidden',
        },
      }}
      extra={
        <Space>
          <Button
            icon={<CheckCircleOutlined/>}
            loading={validating}
            onClick={validate}
          >
            校验
          </Button>
          {!readOnly && (
            <Button icon={<SaveOutlined/>} loading={saving} onClick={save}>
              保存草稿
            </Button>
          )}
          {!readOnly && (
            <Popconfirm
              title="发布后该版本不可修改，确认发布？"
              onConfirm={publish}
            >
              <Button
                type="primary"
                icon={<CloudUploadOutlined/>}
                loading={publishing}
              >
                发布
              </Button>
            </Popconfirm>
          )}
        </Space>
      }
    >
      {readOnly && (
        <Alert
          type="info"
          showIcon
          title="已发布版本不可修改"
          description="如需调整，请从列表使用“创建新版本”，系统会复制当前 DSL 并要求使用新的版本号。"
          style={{marginBottom: 16}}
        />
      )}
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        style={{flex: 1, minHeight: 0}}
        styles={{
          body: {height: '100%', minHeight: 0},
          content: {height: '100%', minHeight: 0},
        }}
        items={[
          {
            key: 'designer',
            label: '可视化编排',
            children: (
              <Row
                gutter={16}
                wrap={false}
                style={{height: '100%', minHeight: 0}}
              >
                <Col
                  flex="auto"
                  style={{display: 'flex', minWidth: 0, overflow: 'hidden'}}
                >
                  <WorkflowCanvas
                    nodes={draft.nodes}
                    edges={draft.edges}
                    selectedNodeId={selectedNodeId}
                    readOnly={readOnly}
                    onChange={(nodes) => updateDraft('nodes', nodes)}
                    onSelect={setSelectedNodeId}
                    onAdd={(type) => {
                      const node = createWorkflowNode(
                        type,
                        draft.nodes.length + 1,
                      );
                      updateDraft('nodes', [...draft.nodes, node]);
                      setSelectedNodeId(node.nodeId);
                    }}
                    onDelete={(nodeId) => {
                      updateDraft(
                        'nodes',
                        draft.nodes.filter((node) => node.nodeId !== nodeId),
                      );
                      updateDraft(
                        'edges',
                        draft.edges.filter(
                          (edge) =>
                            edge.sourceNodeId !== nodeId &&
                            edge.targetNodeId !== nodeId,
                        ),
                      );
                      setSelectedNodeId(undefined);
                    }}
                  />
                </Col>
                <Col
                  flex="390px"
                  style={{height: '100%', minHeight: 0, overflowY: 'auto'}}
                >
                  <NodeInspector
                    node={selectedNode}
                    nodes={draft.nodes}
                    edges={draft.edges}
                    catalog={catalog}
                    toolVersions={toolVersions}
                    readOnly={readOnly}
                    onChange={updateNode}
                    onEdgesChange={(edges) => updateDraft('edges', edges)}
                    onSelectTool={selectTool}
                  />
                </Col>
              </Row>
            ),
          },
          {
            key: 'definition',
            label: '基本定义与 Schema',
            children: (
              <div style={{height: '100%', overflowY: 'auto'}}>
                <DefinitionEditor
                  draft={draft}
                  readOnly={readOnly}
                  onChange={updateDraft}
                />
              </div>
            ),
          },
          {
            key: 'validation',
            label: (
              <span>
                校验结果{' '}
                {issues.length > 0 && <Tag color="error">{issues.length}</Tag>}
              </span>
            ),
            children: (
              <div style={{height: '100%', overflowY: 'auto'}}>
                <ValidationIssues
                  issues={issues}
                  onSelect={(nodeId) => {
                    setSelectedNodeId(nodeId);
                    setActiveTab('designer');
                  }}
                />
              </div>
            ),
          },
        ]}
      />
    </Drawer>
  );
}

function NodeInspector({
                         node,
                         nodes,
                         edges,
                         catalog,
                         toolVersions,
                         readOnly,
                         onChange,
                         onEdgesChange,
                         onSelectTool,
                       }: {
  node?: WorkflowNode;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  catalog: ToolCatalogItem[];
  toolVersions: Record<string, string[]>;
  readOnly: boolean;
  onChange: (node: WorkflowNode) => void;
  onEdgesChange: (edges: WorkflowEdge[]) => void;
  onSelectTool: (key: string) => Promise<void>;
}) {
  if (!node)
    return (
      <Card>
        <Typography.Text type="secondary">请选择一个节点</Typography.Text>
      </Card>
    );
  const toolKey = node.tool
    ? `${node.tool.namespace}/${node.tool.name}`
    : undefined;
  const updateReference = (part: Partial<ToolReference>) =>
    onChange({
      ...node,
      tool: {
        ...(node.tool || {namespace: '', name: '', version: ''}),
        ...part,
      },
    });
  return (
    <Card title={`${nodeTypeLabel(node.type)}节点配置`} size="small">
      <Form layout="vertical" disabled={readOnly}>
        <Form.Item label="节点名称" required>
          <Input
            value={node.name}
            onChange={(event) =>
              onChange({...node, name: event.target.value})
            }
          />
        </Form.Item>
        <Form.Item label="节点 ID">
          <Input value={node.nodeId} disabled/>
        </Form.Item>
        {node.type === 'tool' && (
          <>
            <Form.Item label="已发布工具" required>
              <Select
                showSearch
                value={toolKey}
                options={catalog.map((item) => ({
                  value: `${item.namespace}/${item.name}`,
                  label: `${item.title} (${item.namespace}/${item.name})`,
                }))}
                onChange={(value) =>
                  onSelectTool(value).catch((error) =>
                    message.error(normalizeToolError(error).message).then(),
                  )
                }
              />
            </Form.Item>
            <Form.Item label="固定版本" required>
              <Select
                value={node.tool?.version}
                options={(toolKey ? toolVersions[toolKey] ?? [] : []).map(
                  (version) => ({value: version, label: version}),
                )}
                onChange={(version) => updateReference({version})}
              />
            </Form.Item>
          </>
        )}
        <Form.Item
          label="输入变量映射"
          extra="键为节点输入名，值为变量表达式，例如 input.articleId"
        >
          <JsonEditor
            value={node.inputBindings}
            readOnly={readOnly}
            height={130}
            onChange={(value) => {
              if (isObject(value))
                onChange({...node, inputBindings: stringRecord(value)});
            }}
          />
        </Form.Item>
        <Form.Item label="输出变量名（逗号分隔）">
          <Input
            value={node.outputNames.join(',')}
            onChange={(event) =>
              onChange({
                ...node,
                outputNames: event.target.value
                  .split(',')
                  .map((item) => item.trim())
                  .filter(Boolean),
              })
            }
          />
        </Form.Item>
        <Form.Item label="节点配置">
          <JsonEditor
            value={node.configuration}
            readOnly={readOnly}
            height={150}
            onChange={(value) => {
              if (isObject(value))
                onChange({...node, configuration: value as JsonObject});
            }}
          />
        </Form.Item>
      </Form>
      <Divider titlePlacement="start">出站连线</Divider>
      <List
        size="small"
        dataSource={edges.filter((edge) => edge.sourceNodeId === node.nodeId)}
        locale={{emptyText: '暂无出站连线'}}
        renderItem={(edge) => (
          <List.Item
            actions={
              readOnly
                ? []
                : [
                  <Button
                    key="delete"
                    danger
                    type="link"
                    onClick={() =>
                      onEdgesChange(
                        edges.filter((item) => item.edgeId !== edge.edgeId),
                      )
                    }
                  >
                    删除
                  </Button>,
                ]
            }
          >
            <Space direction="vertical" size={2} style={{width: '100%'}}>
              <Select
                disabled={readOnly}
                value={edge.targetNodeId}
                options={nodes
                  .filter((item) => item.nodeId !== node.nodeId)
                  .map((item) => ({value: item.nodeId, label: item.name}))}
                onChange={(targetNodeId) =>
                  onEdgesChange(
                    edges.map((item) =>
                      item.edgeId === edge.edgeId
                        ? {...item, targetNodeId}
                        : item,
                    ),
                  )
                }
              />
              <Input
                disabled={readOnly}
                placeholder="条件表达式（可选）"
                value={edge.conditionExpression}
                onChange={(event) =>
                  onEdgesChange(
                    edges.map((item) =>
                      item.edgeId === edge.edgeId
                        ? {
                          ...item,
                          conditionExpression:
                            event.target.value || undefined,
                        }
                        : item,
                    ),
                  )
                }
              />
            </Space>
          </List.Item>
        )}
      />
      {!readOnly && node.type !== 'end' && (
        <Button
          block
          icon={<PlusOutlined/>}
          disabled={nodes.length < 2}
          onClick={() => {
            const target = nodes.find((item) => item.nodeId !== node.nodeId);
            if (!target) return;
            onEdgesChange([
              ...edges,
              {
                edgeId: createWorkflowId('edge'),
                sourceNodeId: node.nodeId,
                sourceOutput: node.outputNames[0],
                targetNodeId: target.nodeId,
                targetInput: 'input',
              },
            ]);
          }}
        >
          添加连线
        </Button>
      )}
    </Card>
  );
}

function DefinitionEditor({
                            draft,
                            readOnly,
                            onChange,
                          }: {
  draft: WorkflowDraftRequest;
  readOnly: boolean;
  onChange: <K extends keyof WorkflowDraftRequest>(
    key: K,
    value: WorkflowDraftRequest[K],
  ) => void;
}) {
  return (
    <Row gutter={24}>
      <Col xs={24} lg={10}>
        <Form layout="vertical" disabled={readOnly}>
          <Form.Item label="工作流 ID" required>
            <Input
              value={draft.workflowId}
              disabled={Boolean(draft.expectedRowVersion) || readOnly}
              onChange={(event) => onChange('workflowId', event.target.value)}
            />
          </Form.Item>
          <Form.Item
            label="版本"
            required
            extra="建议使用语义化版本，例如 1.0.0"
          >
            <Input
              value={draft.version}
              disabled={Boolean(draft.expectedRowVersion) || readOnly}
              onChange={(event) => onChange('version', event.target.value)}
            />
          </Form.Item>
          <Form.Item label="名称" required>
            <Input
              value={draft.name}
              onChange={(event) => onChange('name', event.target.value)}
            />
          </Form.Item>
          <Form.Item label="描述">
            <Input.TextArea
              rows={3}
              value={draft.description}
              onChange={(event) => onChange('description', event.target.value)}
            />
          </Form.Item>
          <Form.Item label="标签">
            <Select
              mode="tags"
              value={draft.tags}
              onChange={(value) => onChange('tags', value)}
            />
          </Form.Item>
          <Divider titlePlacement="start">执行预算</Divider>
          <Form.Item label="最大步骤数">
            <InputNumber
              min={1}
              max={10000}
              value={draft.executionPolicy.maximumSteps}
              onChange={(value) =>
                onChange('executionPolicy', {
                  ...draft.executionPolicy,
                  maximumSteps: value || 1,
                })
              }
            />
          </Form.Item>
          <Form.Item label="总超时（ISO-8601）">
            <Input
              value={draft.executionPolicy.timeout}
              onChange={(event) =>
                onChange('executionPolicy', {
                  ...draft.executionPolicy,
                  timeout: event.target.value,
                })
              }
            />
          </Form.Item>
          <Form.Item label="节点最大重试">
            <InputNumber
              min={0}
              value={draft.executionPolicy.maximumNodeRetries}
              onChange={(value) =>
                onChange('executionPolicy', {
                  ...draft.executionPolicy,
                  maximumNodeRetries: value || 0,
                })
              }
            />
          </Form.Item>
          <Form.Item label="最大并行度">
            <InputNumber
              min={1}
              value={draft.executionPolicy.maximumParallelism}
              onChange={(value) =>
                onChange('executionPolicy', {
                  ...draft.executionPolicy,
                  maximumParallelism: value || 1,
                })
              }
            />
          </Form.Item>
          <Form.Item label="每个节点后保存检查点">
            <Switch
              checked={draft.executionPolicy.checkpointAfterEachNode}
              onChange={(value) =>
                onChange('executionPolicy', {
                  ...draft.executionPolicy,
                  checkpointAfterEachNode: value,
                })
              }
            />
          </Form.Item>
        </Form>
      </Col>
      <Col xs={24} lg={14}>
        <Typography.Title level={5}>输入 JSON Schema</Typography.Title>
        <JsonEditor
          value={parseSchema(draft.inputSchema.schema)}
          readOnly={readOnly}
          height={260}
          onChange={(value) => {
            if (isObject(value))
              onChange('inputSchema', {
                ...draft.inputSchema,
                schema: JSON.stringify(value, null, 2),
              });
          }}
        />
        <Typography.Title level={5} style={{marginTop: 20}}>
          输出 JSON Schema
        </Typography.Title>
        <JsonEditor
          value={parseSchema(draft.outputSchema.schema)}
          readOnly={readOnly}
          height={260}
          onChange={(value) => {
            if (isObject(value))
              onChange('outputSchema', {
                ...draft.outputSchema,
                schema: JSON.stringify(value, null, 2),
              });
          }}
        />
      </Col>
    </Row>
  );
}

function ValidationIssues({
                            issues,
                            onSelect,
                          }: {
  issues: WorkflowValidationIssue[];
  onSelect: (nodeId: string) => void;
}) {
  if (issues.length === 0)
    return (
      <Alert
        type="info"
        showIcon
        message="尚未校验"
        description="保存或发布前请运行服务端完整校验。"
      />
    );
  return (
    <List
      dataSource={issues}
      renderItem={(issue) => (
        <List.Item
          actions={
            issue.nodeId
              ? [
                <Button
                  key="locate"
                  type="link"
                  onClick={() => issue.nodeId && onSelect(issue.nodeId)}
                >
                  定位节点
                </Button>,
              ]
              : []
          }
        >
          <List.Item.Meta
            title={
              <Space>
                <Tag
                  color={
                    issue.severity === 'ERROR'
                      ? 'error'
                      : issue.severity === 'WARNING'
                        ? 'warning'
                        : 'blue'
                  }
                >
                  {issue.severity}
                </Tag>
                {issue.code}
              </Space>
            }
            description={issue.message}
          />
        </List.Item>
      )}
    />
  );
}

function parseSchema(value: string): JsonObject {
  try {
    const parsed = JSON.parse(value);
    return isObject(parsed) ? (parsed as JsonObject) : {};
  } catch {
    return {};
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function stringRecord(value: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [key, String(item)]),
  );
}

function nextVersion(version: string): string {
  const parts = version.split('.').map(Number);
  if (parts.length === 3 && parts.every(Number.isFinite))
    return `${parts[0]}.${parts[1]}.${parts[2] + 1}`;
  return `${version}-next`;
}
