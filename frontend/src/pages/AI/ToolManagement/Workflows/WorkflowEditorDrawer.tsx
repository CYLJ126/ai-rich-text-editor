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
  asControlFlowEdge,
  createWorkflowDraft,
  createWorkflowEdge,
  createWorkflowNode,
  nodeTypeLabel,
  normalizeToolError,
} from '@/features/ai-tool';
import {listToolBindings, listToolCatalog, listToolVersions,} from '@/services/ant-design-pro/ai.tool';
import {publishWorkflowVersion, saveWorkflowDraft, validateWorkflow,} from '@/services/ant-design-pro/ai.tool.workflow';
import type {
  JsonObject,
  ToolBindingView,
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
  const [hasValidated, setHasValidated] = useState(false);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [catalog, setCatalog] = useState<ToolCatalogItem[]>([]);
  const [bindings, setBindings] = useState<ToolBindingView[]>([]);
  const [bindingsLoading, setBindingsLoading] = useState(false);
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
    setHasValidated(false);
    listToolCatalog({lifecycleState: 'published', current: 1, pageSize: 200})
      .then((page) => setCatalog(page.records))
      .catch(() => setCatalog([]));
  }, [clonePublished, initial, open]);

  const selectedNode = useMemo(
    () => draft.nodes.find((node) => node.nodeId === selectedNodeId),
    [draft.nodes, selectedNodeId],
  );
  const bindingWorkspaceId = configurationText(
    selectedNode?.configuration.workspaceId,
  );

  useEffect(() => {
    if (!open || selectedNode?.type !== 'tool') {
      setBindings([]);
      return;
    }
    let active = true;
    setBindingsLoading(true);
    listToolBindings(bindingWorkspaceId)
      .then((items) => {
        if (active) setBindings(items);
      })
      .catch(() => {
        if (active) setBindings([]);
      })
      .finally(() => {
        if (active) setBindingsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [bindingWorkspaceId, open, selectedNode?.nodeId, selectedNode?.type]);

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
  ) => {
    setDraft((current) => ({...current, [key]: value}));
    setIssues([]);
    setHasValidated(false);
  };

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
      configuration: withoutBinding(selectedNode.configuration),
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
    setIssues([]);
    setHasValidated(false);
    try {
      const result = await validateWorkflow({
        ...draft,
        expectedRowVersion: rowVersion,
      });
      const resultIssues = result.issues ?? [];
      const valid = result.valid
        ?? resultIssues.every((issue) => issue.severity !== 'ERROR');
      setIssues(resultIssues);
      setHasValidated(true);
      if (valid) message.success('工作流校验通过').then();
      else message.warning('校验发现阻断问题，请按提示修正').then();
      return valid;
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
                    bindings={bindings}
                    bindingsLoading={bindingsLoading}
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
                  validated={hasValidated}
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
                         bindings,
                         bindingsLoading,
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
  bindings: ToolBindingView[];
  bindingsLoading: boolean;
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
      configuration: withoutBinding(node.configuration),
    });
  const bindingId = configurationText(node.configuration.bindingId);
  const selectedTool = node.tool;
  const matchingBindings = selectedTool
    ? bindings.filter((binding) => sameTool(binding.tool, selectedTool))
    : [];
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
            <Form.Item
              label="工作空间 ID（可选）"
              extra="留空使用个人绑定；填写后可选择该工作空间或个人绑定"
            >
              <Input
                value={configurationText(node.configuration.workspaceId) || ''}
                onChange={(event) => {
                  const configuration = withoutBinding(node.configuration);
                  const workspaceId = event.target.value.trim();
                  if (workspaceId) configuration.workspaceId = workspaceId;
                  else delete configuration.workspaceId;
                  onChange({...node, configuration});
                }}
              />
            </Form.Item>
            <Form.Item
              label="工具绑定"
              required
              extra={`当前作用域：${configurationText(node.configuration.workspaceId) || '个人'}。请先在“用户绑定”中创建与上方工具及版本一致的绑定。`}
            >
              <Select
                aria-label="工具绑定"
                allowClear
                showSearch
                loading={bindingsLoading}
                value={bindingId}
                placeholder="请选择当前用户可用的工具绑定"
                notFoundContent={
                  bindingsLoading
                    ? '正在加载工具绑定'
                    : '当前作用域没有此工具版本的绑定'
                }
                options={matchingBindings.map((binding) => ({
                  value: binding.bindingId,
                  label: `${binding.bindingId}${binding.workspaceId ? ` · ${binding.workspaceId}` : ' · 个人'}`,
                  disabled: !binding.enabled || !binding.available,
                }))}
                onChange={(nextBindingId?: string) => {
                  const configuration = {...node.configuration};
                  if (nextBindingId) configuration.bindingId = nextBindingId;
                  else delete configuration.bindingId;
                  onChange({...node, configuration});
                }}
              />
            </Form.Item>
          </>
        )}
        <Form.Item
          label="输入变量映射"
          extra="键为节点输入名，值为变量表达式，例如 ${inputs.articleId} 或 ${toolNodeId.result}"
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
        <Form.Item
          label="节点配置"
          extra="用于节点元数据（如 bindingId、workspaceId 和 _ui），工具参数请配置在输入变量映射中"
        >
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
        renderItem={(edge) => {
          const updateEdge = (changes: Partial<WorkflowEdge>) =>
            onEdgesChange(
              edges.map((item) =>
                item.edgeId === edge.edgeId ? {...item, ...changes} : item,
              ),
            );
          return (
            <List.Item
              actions={
                readOnly
                  ? []
                  : [
                    <Button
                      key="control-only"
                      type="link"
                      disabled={!edge.sourceOutput && !edge.targetInput}
                      onClick={() =>
                        onEdgesChange(
                          edges.map((item) =>
                            item.edgeId === edge.edgeId
                              ? asControlFlowEdge(item)
                              : item,
                          ),
                        )
                      }
                    >
                      仅控制顺序
                    </Button>,
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
              <Space direction="vertical" size={6} style={{width: '100%'}}>
                <Select
                  aria-label={`${node.name} 连线目标节点`}
                  disabled={readOnly}
                  value={edge.targetNodeId}
                  options={nodes
                    .filter((item) => item.nodeId !== node.nodeId)
                    .map((item) => ({value: item.nodeId, label: item.name}))}
                  onChange={(targetNodeId) =>
                    updateEdge({targetNodeId, targetInput: undefined})
                  }
                />
                <Typography.Text type="secondary">
                  数据映射（可选，两项均留空时仅控制执行顺序）
                </Typography.Text>
                <Select
                  allowClear
                  aria-label={`${node.name} 连线源输出`}
                  disabled={readOnly}
                  placeholder="源输出"
                  value={edge.sourceOutput || undefined}
                  options={node.outputNames.map((output) => ({
                    value: output,
                    label: output,
                  }))}
                  onChange={(sourceOutput) =>
                    updateEdge({sourceOutput: sourceOutput || undefined})
                  }
                />
                <Input
                  allowClear
                  aria-label={`${node.name} 连线目标输入`}
                  disabled={readOnly}
                  placeholder="目标输入"
                  value={edge.targetInput || ''}
                  onChange={(event) =>
                    updateEdge({targetInput: event.target.value || undefined})
                  }
                />
                <Input
                  allowClear
                  aria-label={`${node.name} 连线条件`}
                  disabled={readOnly}
                  placeholder="条件表达式（可选）"
                  value={edge.conditionExpression || ''}
                  onChange={(event) =>
                    updateEdge({
                      conditionExpression: event.target.value || undefined,
                    })
                  }
                />
              </Space>
            </List.Item>
          );
        }}
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
              createWorkflowEdge(node.nodeId, target.nodeId),
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
                            validated,
                            onSelect,
                          }: {
  issues: WorkflowValidationIssue[];
  validated: boolean;
  onSelect: (nodeId: string) => void;
}) {
  if (!validated)
    return (
      <Alert
        type="info"
        showIcon
        title="尚未校验"
        description="保存或发布前请运行服务端完整校验。"
      />
    );
  if (issues.length === 0)
    return (
      <Alert
        type="success"
        showIcon
        title="校验通过"
        description="未发现阻断问题，可以保存或发布当前工作流。"
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

function configurationText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function withoutBinding(configuration: JsonObject): JsonObject {
  const next = {...configuration};
  delete next.bindingId;
  return next;
}

function sameTool(left: ToolReference, right: ToolReference): boolean {
  return left.namespace === right.namespace
    && left.name === right.name
    && left.version === right.version;
}

function nextVersion(version: string): string {
  const parts = version.split('.').map(Number);
  if (parts.length === 3 && parts.every(Number.isFinite))
    return `${parts[0]}.${parts[1]}.${parts[2] + 1}`;
  return `${version}-next`;
}
