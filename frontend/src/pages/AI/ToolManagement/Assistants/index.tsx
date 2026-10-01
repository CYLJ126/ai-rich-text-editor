import {ArrowDownOutlined, ArrowUpOutlined, EyeOutlined, SaveOutlined, SettingOutlined,} from '@ant-design/icons';
import {useAccess} from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {JsonEditor} from '@/components';
import {
  compactPolicyOverride,
  normalizeToolError,
  toolReferenceLabel,
  validateAssistantToolCommands,
} from '@/features/ai-tool';
import {
  getAssistantToolConfiguration,
  getAssistantToolDefinitions,
  listAssistantToolOptions,
  listToolBindings,
  replaceAssistantTools,
} from '@/services/ant-design-pro/ai.tool';
import type {
  AssistantToolCommand,
  AssistantToolOptionView,
  ModelToolDefinition,
  ToolBindingView,
  ToolPolicyOverride,
} from '@/types/ai.tool.type';
import {PolicyOverrideFields, ToolManagementPage} from '../components';

export default function AssistantToolsPage() {
  const access = useAccess();
  const [policyForm] = Form.useForm<ToolPolicyOverride>();
  const [assistants, setAssistants] = useState<AssistantToolOptionView[]>([]);
  const [assistantId, setAssistantId] = useState<number>();
  const [scopeDraft, setScopeDraft] = useState('');
  const [workspaceId, setWorkspaceId] = useState<string>();
  const [bindings, setBindings] = useState<ToolBindingView[]>([]);
  const [commands, setCommands] = useState<AssistantToolCommand[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [policyIndex, setPolicyIndex] = useState<number>();
  const [definitions, setDefinitions] = useState<ModelToolDefinition[]>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    listAssistantToolOptions()
      .then((items) => {
        setAssistants(items);
        setAssistantId(
          (current) =>
            current ||
            items.find((item) => item.enabled)?.assistantId ||
            items[0]?.assistantId,
        );
      })
      .catch((error) =>
        message.error(normalizeToolError(error).message).then(),
      );
  }, []);

  const load = useCallback(async () => {
    if (!assistantId) return;
    setLoading(true);
    try {
      const [nextBindings, nextCommands] = await Promise.all([
        listToolBindings(workspaceId),
        getAssistantToolConfiguration(assistantId),
      ]);
      setBindings(nextBindings);
      setCommands(
        [...nextCommands].sort(
          (left, right) => left.sortOrder - right.sortOrder,
        ),
      );
      setDirty(false);
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setLoading(false);
    }
  }, [assistantId, workspaceId]);

  useEffect(() => {
    load().then();
  }, [load]);

  const bindingById = useMemo(
    () => new Map(bindings.map((binding) => [binding.bindingId, binding])),
    [bindings],
  );
  const selectedIds = commands.map((command) => command.bindingId);
  const selectedAssistant = assistants.find(
    (assistant) => assistant.assistantId === assistantId,
  );

  const selectBindings = (bindingIds: string[]) => {
    const commandById = new Map(
      commands.map((command) => [command.bindingId, command]),
    );
    setCommands(
      bindingIds.map(
        (bindingId, index) =>
          commandById.get(bindingId) || {
            bindingId,
            enabled: true,
            sortOrder: index,
          },
      ),
    );
    setDirty(true);
  };

  const updateCommand = (
    index: number,
    patch: Partial<AssistantToolCommand>,
  ) => {
    setCommands((current) =>
      current.map((command, commandIndex) =>
        commandIndex === index ? {...command, ...patch} : command,
      ),
    );
    setDirty(true);
  };

  const move = (index: number, offset: number) => {
    setCommands((current) => {
      const target = index + offset;
      if (target < 0 || target >= current.length) return current;
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next.map((command, sortOrder) => ({...command, sortOrder}));
    });
    setDirty(true);
  };

  const save = async () => {
    if (!assistantId) return;
    const normalized = commands.map((command, sortOrder) => ({
      ...command,
      sortOrder,
    }));
    const errors = validateAssistantToolCommands(normalized, bindings);
    if (errors.length) {
      Modal.error({
        title: '助手工具配置未通过校验',
        content: errors.map((error) => <div key={error}>{error}</div>),
      });
      return;
    }
    setSaving(true);
    try {
      await replaceAssistantTools(assistantId, {
        workspaceId,
        tools: normalized,
      });
      setCommands(normalized);
      setDirty(false);
      message.success('助手工具配置已保存').then();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setSaving(false);
    }
  };

  const changeAssistant = (nextAssistantId: number) => {
    if (!dirty) {
      setAssistantId(nextAssistantId);
      return;
    }
    Modal.confirm({
      title: '放弃未保存的助手工具配置？',
      content: '切换助手后，本页尚未保存的顺序、启用状态和策略修改将丢失。',
      okText: '放弃并切换',
      okButtonProps: {danger: true},
      onOk: () => setAssistantId(nextAssistantId),
    });
  };

  const changeWorkspace = (value: string) => {
    const nextWorkspaceId = value.trim() || undefined;
    if (!dirty) {
      setWorkspaceId(nextWorkspaceId);
      return;
    }
    Modal.confirm({
      title: '放弃未保存的助手工具配置？',
      content: '切换工作空间作用域后，本页尚未保存的修改将丢失。',
      okText: '放弃并切换',
      okButtonProps: {danger: true},
      onOk: () => setWorkspaceId(nextWorkspaceId),
    });
  };

  const preview = async () => {
    if (!assistantId) return;
    setPreviewLoading(true);
    try {
      setDefinitions(
        await getAssistantToolDefinitions(assistantId, workspaceId),
      );
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setPreviewLoading(false);
    }
  };

  const columns: ColumnsType<AssistantToolCommand> = [
    {
      title: '顺序',
      width: 105,
      render: (_, __, index) => (
        <Space size={4}>
          <Button
            size="small"
            icon={<ArrowUpOutlined/>}
            disabled={index === 0 || !access.canConfigureAiTools}
            onClick={() => move(index, -1)}
          />
          <Button
            size="small"
            icon={<ArrowDownOutlined/>}
            disabled={index === commands.length - 1 || !access.canConfigureAiTools}
            onClick={() => move(index, 1)}
          />
        </Space>
      ),
    },
    {
      title: '绑定的当前执行版本',
      render: (_, command) => {
        const binding = bindingById.get(command.bindingId);
        return binding ? (
          <Space direction="vertical" size={0}>
            <Typography.Text>
              {toolReferenceLabel(binding.tool)}
            </Typography.Text>
            <Typography.Text type="secondary" copyable>
              {binding.bindingId}
            </Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="danger">
            作用域中不存在：{command.bindingId}
          </Typography.Text>
        );
      },
    },
    {
      title: '可用性',
      width: 100,
      render: (_, command) => {
        const binding = bindingById.get(command.bindingId);
        return binding?.enabled && binding.available ? (
          <Tag color="success">可用</Tag>
        ) : (
          <Tag color="error">不可用</Tag>
        );
      },
    },
    {
      title: '模型启用',
      width: 100,
      render: (_, command, index) => (
        <Switch
          checked={command.enabled}
          disabled={!access.canConfigureAiTools}
          onChange={(enabled) => updateCommand(index, {enabled})}
        />
      ),
    },
    {
      title: '策略',
      width: 120,
      render: (_, command, index) => (
        <Button
          type="link"
          icon={<SettingOutlined/>}
          disabled={!access.canConfigureAiTools}
          onClick={() => {
            setPolicyIndex(index);
            policyForm.setFieldsValue(command.policyOverride || {});
          }}
        >
          {command.policyOverride ? '已收紧' : '继承'}
        </Button>
      ),
    },
    {
      title: '操作',
      width: 80,
      render: (_, command) => (
        <Typography.Link
          type="danger"
          disabled={!access.canConfigureAiTools}
          onClick={() =>
            selectBindings(
              selectedIds.filter(
                (bindingId) => bindingId !== command.bindingId,
              ),
            )
          }
        >
          移除
        </Typography.Link>
      ),
    },
  ];

  return (
    <ToolManagementPage
      activeKey="assistants"
      title="助手工具配置"
      subTitle="按固定顺序配置模型可见工具，所有实际调用仍经过 ToolGateway"
      extra={
        <Space>
          <Button
            icon={<EyeOutlined/>}
            loading={previewLoading}
            disabled={!assistantId || dirty}
            title={dirty ? '请先保存配置后再预览模型定义' : undefined}
            onClick={preview}
          >
            预览模型定义
          </Button>
          {access.canConfigureAiTools && (
            <Button
              type="primary"
              icon={<SaveOutlined/>}
              loading={saving}
              disabled={!assistantId}
              onClick={save}
            >
              保存配置
            </Button>
          )}
        </Space>
      }
    >
      <Card loading={loading}>
        <Row gutter={16}>
          <Col xs={24} lg={10}>
            <Typography.Text strong>AI 助手</Typography.Text>
            <Select
              value={assistantId}
              onChange={changeAssistant}
              style={{width: '100%', marginTop: 8}}
              placeholder="请选择助手"
              options={assistants.map((assistant) => ({
                value: assistant.assistantId,
                label: `${assistant.name}${assistant.enabled ? '' : '（已禁用）'}`,
              }))}
            />
          </Col>
          <Col xs={24} lg={10}>
            <Typography.Text strong>工作空间作用域</Typography.Text>
            <Input.Search
              value={scopeDraft}
              onChange={(event) => setScopeDraft(event.target.value)}
              onSearch={changeWorkspace}
              enterButton="应用"
              allowClear
              placeholder="留空使用个人作用域"
              style={{marginTop: 8}}
            />
          </Col>
          <Col xs={24} lg={4}>
            <Typography.Text strong>当前状态</Typography.Text>
            <div style={{marginTop: 12}}>
              {selectedAssistant?.enabled ? (
                <Tag color="success">助手已启用</Tag>
              ) : (
                <Tag color="warning">助手已禁用</Tag>
              )}
              {dirty && <Tag color="processing">有未保存修改</Tag>}
            </div>
          </Col>
        </Row>
      </Card>

      {assistantId ? (
        <Card title="工具集合" style={{marginTop: 16}}>
          <Typography.Paragraph type="secondary">
            只能选择当前作用域内已启用且可用的绑定。工具名称相同的多个版本不能同时向模型启用。
          </Typography.Paragraph>
          <Select
            mode="multiple"
            value={selectedIds}
            disabled={!access.canConfigureAiTools}
            onChange={selectBindings}
            optionFilterProp="label"
            placeholder="添加工具绑定"
            style={{width: '100%', marginBottom: 16}}
            options={bindings
              .filter((binding) => binding.enabled && binding.available)
              .map((binding) => ({
                value: binding.bindingId,
                label: toolReferenceLabel(binding.tool),
              }))}
          />
          <Table<AssistantToolCommand>
            rowKey="bindingId"
            columns={columns}
            dataSource={commands}
            pagination={false}
            locale={{emptyText: <Empty description="尚未配置工具"/>}}
            scroll={{x: 900}}
          />
        </Card>
      ) : (
        <Card style={{marginTop: 16}}>
          <Empty description="当前用户没有可配置的助手"/>
        </Card>
      )}

      <Modal
        title="助手级策略覆盖"
        open={policyIndex !== undefined}
        destroyOnHidden
        onCancel={() => {
          setPolicyIndex(undefined);
          policyForm.resetFields();
        }}
        onOk={() => policyForm.submit()}
      >
        <Typography.Paragraph type="secondary">
          此处只能进一步收紧绑定层的有效策略。
        </Typography.Paragraph>
        <Form
          form={policyForm}
          layout="vertical"
          onFinish={(values) => {
            if (policyIndex !== undefined)
              updateCommand(policyIndex, {
                policyOverride: compactPolicyOverride(values),
              });
            setPolicyIndex(undefined);
            policyForm.resetFields();
          }}
        >
          <PolicyOverrideFields/>
        </Form>
      </Modal>

      <Drawer
        title="模型可见工具定义"
        width={760}
        open={definitions !== undefined}
        onClose={() => setDefinitions(undefined)}
      >
        {definitions?.length ? (
          definitions.map((definition) => (
            <Card
              key={definition.name}
              title={definition.name}
              size="small"
              style={{marginBottom: 16}}
            >
              <Typography.Paragraph>
                {definition.description}
              </Typography.Paragraph>
              <JsonEditor
                value={parseSchema(definition.inputSchema)}
                readOnly
                height={220}
              />
            </Card>
          ))
        ) : (
          <Empty description="当前助手没有可暴露给模型的工具"/>
        )}
      </Drawer>
    </ToolManagementPage>
  );
}

function parseSchema(schema: string): unknown {
  try {
    return JSON.parse(schema);
  } catch {
    return {invalidSchema: schema};
  }
}
