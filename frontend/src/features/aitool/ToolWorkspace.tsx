import React, {useCallback, useEffect, useMemo, useState} from 'react';
import ToolApprovalInbox from './ToolApprovalInbox';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {ReloadOutlined, ToolOutlined} from '@ant-design/icons';
import type {WorkspaceView} from '@/services/platform/workspace';
import {
  defaultBindingRequest,
  listToolBindings,
  listTools,
  type ToolBinding,
  type ToolFilters,
  toolRefKey,
  type ToolSummary,
} from '@/services/arte-ai/tool';
import {registerWorkspaceGuard} from '@/features/workspace/useWorkspace';
import {useToolPage} from './useToolPage';
import {useToolCommand} from './useToolCommand';
import ToolDetailPanel, {releaseName} from './ToolDetailPanel';
import BindingEditor from './BindingEditor';
import ToolRunPanel from './ToolRunPanel';
import './tools.css';

export default function ToolWorkspace({
                                        userId,
                                        workspace,
                                      }: {
  userId: string;
  workspace: WorkspaceView;
}) {
  const {tenantId, workspaceId} = workspace.scope;
  // 同空间元数据刷新不重置编辑器；真正切换由页面的账号／Scope key 隔离。
  const scope = useMemo(
    () => ({tenantId, workspaceId}),
    [tenantId, workspaceId],
  );
  const [filters, setFilters] = useState<ToolFilters>({});
  const [tab, setTab] = useState('catalog');
  const [selectedTool, setSelectedTool] = useState<ToolSummary | null>(null);
  const [selectedBinding, setSelectedBinding] = useState<ToolBinding | null>(
    null,
  );
  const [bindingDirty, setDirty] = useState(false);
  const [runDirty, setRunDirty] = useState(false);
  const [runBinding, setRunBinding] = useState<string>();
  const dirty = bindingDirty || runDirty;
  const [bindingRevision, setBindingRevision] = useState(0);
  const [sessionCleared, setSessionCleared] = useState(false);
  const [modal, modalContext] = Modal.useModal();
  const catalogLoad = useCallback(
    (cursor: string | null, signal: AbortSignal) =>
      listTools(scope, cursor, {signal}, filters),
    [scope, filters],
  );
  const bindingsLoad = useCallback(
    (cursor: string | null, signal: AbortSignal) =>
      listToolBindings(scope, cursor, {signal}),
    [scope, bindingRevision],
  );
  const catalog = useToolPage(catalogLoad);
  const bindings = useToolPage(bindingsLoad);
  const command = useToolCommand({userId, scope}, (receipt) => {
    setSelectedTool(null);
    setSelectedBinding(receipt.binding);
    setTab('bindings');
    setDirty(false);
    setBindingRevision((value) => value + 1);
  });
  const protect = useCallback(
    (next: () => void) => {
      if (!dirty) {
        next();
        return;
      }
      modal.confirm({
        title: '有尚未保存的配置',
        content: '切换后将放弃当前输入。已发送的操作仍可在原空间确认。',
        okText: '放弃输入并切换',
        cancelText: '继续编辑',
        onOk: () => {
          setDirty(false);
          setRunDirty(false);
          next();
        },
      });
    },
    [dirty, modal],
  );
  useEffect(
    () =>
      registerWorkspaceGuard(userId, (_nextScope, proceed) => protect(proceed)),
    [userId, protect],
  );
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    const clear = (event: Event) => {
      if ((event as CustomEvent<string>).detail === userId)
        setSessionCleared(true);
    };
    window.addEventListener('beforeunload', beforeUnload);
    window.addEventListener('arte-account-session-cleared', clear);
    return () => {
      window.removeEventListener('beforeunload', beforeUnload);
      window.removeEventListener('arte-account-session-cleared', clear);
    };
  }, [dirty, userId]);
  const disabled = command.busy || !!command.pending || command.blocked;
  if (sessionCleared)
    return <Alert type="info" title="账号会话已变化，请重新登录或刷新页面。"/>;
  const close = () =>
    protect(() => {
      setSelectedTool(null);
      setSelectedBinding(null);
    });
  return (
    <section className="tool-workspace" aria-label="当前空间工具">
      {modalContext}
      {command.error && <Alert type="warning" showIcon title={command.error}/>}
      {command.pending && (
        <Alert
          type="info"
          showIcon
          title="原配置操作尚待确认"
          description="请确认原操作；重复确认会复用原输入和原命令键。切换空间不会撤销服务器操作，回到原空间可继续确认。"
          action={
            <Button
              loading={command.busy}
              disabled={command.blocked}
              onClick={command.retry}
            >
              确认原操作
            </Button>
          }
        />
      )}
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          {key: 'approvals', label: '确认与审批', children: <ToolApprovalInbox context={{userId, scope}}/>},
          {
            key: 'run', label: '工具调用', forceRender: true,
            children: <ToolRunPanel context={{userId, scope}} bindings={bindings.records} selectedBindingId={runBinding}
                                    onDirty={setRunDirty}/>,
          },
          {
            key: 'catalog',
            label: '工具目录',
            children: (
              <Flex vertical gap={16}>
                <Form
                  className="tool-filters"
                  layout="inline"
                  onFinish={(values) =>
                    setFilters(
                      Object.fromEntries(
                        Object.entries(values)
                          .filter(
                            ([, value]) =>
                              typeof value === 'string' && value.trim(),
                          )
                          .map(([key, value]) => [key, String(value).trim()]),
                      ),
                    )
                  }
                >
                  <Form.Item name="query">
                    <Input
                      aria-label="搜索工具"
                      placeholder="名称或用途"
                      allowClear
                      maxLength={200}
                    />
                  </Form.Item>
                  <Form.Item name="tag">
                    <Input
                      aria-label="工具标签"
                      placeholder="标签"
                      allowClear
                      maxLength={100}
                    />
                  </Form.Item>
                  <Form.Item name="providerId">
                    <Input
                      aria-label="提供者"
                      placeholder="提供者标识"
                      allowClear
                      maxLength={256}
                    />
                  </Form.Item>
                  <Form.Item name="state">
                    <Select
                      aria-label="发布状态"
                      placeholder="发布状态"
                      allowClear
                      style={{minWidth: 120}}
                      options={['PUBLISHED', 'DEPRECATED', 'DISABLED'].map(
                        (value) => ({value, label: releaseName[value]}),
                      )}
                    />
                  </Form.Item>
                  <Button type="primary" htmlType="submit">
                    筛选
                  </Button>
                  <Button
                    icon={<ReloadOutlined/>}
                    onClick={catalog.refresh}
                    loading={catalog.loading}
                  >
                    刷新
                  </Button>
                </Form>
                {catalog.error && (
                  <Alert
                    type="warning"
                    title={catalog.error}
                    action={<Button onClick={catalog.refresh}>重新读取</Button>}
                  />
                )}
                {!catalog.records.length &&
                  !catalog.loading &&
                  !catalog.error && (
                    <Empty
                      description={
                        catalog.exhausted
                          ? '当前筛选条件下暂无可见工具'
                          : '本页暂无匹配项，可继续加载目录'
                      }
                    />
                  )}
                <div className="tool-grid">
                  {catalog.records.map((tool) => (
                    <Card
                      key={toolRefKey(tool.tool)}
                      size="small"
                      title={
                        <Space>
                          <ToolOutlined/>
                          {tool.title}
                        </Space>
                      }
                      extra={
                        <Tag>
                          {releaseName[tool.release.state] ??
                            tool.release.state}
                        </Tag>
                      }
                    >
                      <Typography.Paragraph
                        ellipsis={{rows: 3, expandable: true}}
                      >
                        {tool.description}
                      </Typography.Paragraph>
                      <Flex gap={4} wrap>
                        {tool.tags.map((tag) => (
                          <Tag key={tag}>{tag}</Tag>
                        ))}
                        <Tag
                          color={
                            tool.risk.sideEffect === 'READ_ONLY'
                              ? 'green'
                              : 'orange'
                          }
                        >
                          {tool.risk.sideEffect === 'READ_ONLY'
                            ? '只读'
                            : '有业务副作用'}
                        </Tag>
                      </Flex>
                      <Typography.Paragraph type="secondary">
                        {tool.provider.id} · {tool.tool.version}
                      </Typography.Paragraph>
                      <Button
                        onClick={() =>
                          protect(() => {
                            setSelectedBinding(null);
                            setSelectedTool(tool);
                          })
                        }
                      >
                        查看并准备配置
                      </Button>
                    </Card>
                  ))}
                </div>
                <Button
                  loading={catalog.loading}
                  disabled={catalog.exhausted || !!catalog.error}
                  onClick={catalog.more}
                >
                  {catalog.exhausted ? '目录已加载完毕' : '继续加载目录'}
                </Button>
              </Flex>
            ),
          },
          {
            key: 'bindings',
            label: '我的工具配置',
            children: (
              <Flex vertical gap={12}>
                <Flex justify="space-between" wrap gap={8}>
                  <Typography.Text type="secondary">
                    仅显示本人在 {workspace.name}{' '}
                    的配置；默认配置重复准备不会覆盖已有选择。
                  </Typography.Text>
                  <Button loading={bindings.loading} onClick={bindings.refresh}>
                    刷新配置
                  </Button>
                </Flex>
                {bindings.error && (
                  <Alert
                    type="warning"
                    title={bindings.error}
                    action={
                      <Button onClick={bindings.refresh}>重新读取</Button>
                    }
                  />
                )}
                {!bindings.records.length &&
                  !bindings.loading &&
                  !bindings.error && (
                    <Empty description="尚未准备工具配置，请从工具目录开始"/>
                  )}
                {bindings.records.map((binding) => (
                  <Card size="small" key={binding.bindingId}>
                    <Flex justify="space-between" align="center" gap={12} wrap>
                      <Flex vertical gap={4}>
                        <Typography.Text strong>{binding.name}</Typography.Text>
                        <Typography.Text type="secondary">
                          {binding.toolId}
                        </Typography.Text>
                        <Flex gap={4}>
                          <Tag>{binding.enabled ? '已启用' : '已停用'}</Tag>
                          {binding.bindingId.startsWith('default-tool-') && (
                            <Tag color="blue">默认配置</Tag>
                          )}
                        </Flex>
                      </Flex>
                      <Space wrap>
                        <Button type="primary" disabled={!binding.enabled} onClick={() => protect(() => {
                          setRunBinding(binding.bindingId);
                          setTab('run');
                        })}>调用工具</Button>
                      <Button
                        onClick={() =>
                          protect(() => {
                            setSelectedTool(null);
                            setSelectedBinding(binding);
                          })
                        }
                      >
                        管理配置
                      </Button> </Space>
                    </Flex>
                  </Card>
                ))}
                <Button
                  loading={bindings.loading}
                  disabled={bindings.exhausted || !!bindings.error}
                  onClick={bindings.more}
                >
                  {bindings.exhausted ? '配置已加载完毕' : '继续加载配置'}
                </Button>
              </Flex>
            ),
          },
        ]}
      />
      <Drawer
        open={!!selectedTool || !!selectedBinding}
        title={selectedTool ? '工具详情' : '我的工具配置'}
        onClose={close}
        size="large"
        destroyOnHidden
        extra={
          command.pending && (
            <Button loading={command.busy} onClick={command.retry}>
              确认原操作
            </Button>
          )
        }
      >
        {command.error && (
          <Alert
            type="warning"
            title={command.error}
            className="tool-drawer-alert"
          />
        )}
        {command.pending && (
          <Alert type="info" title="原配置操作尚待确认；请勿新建另一条操作。"/>
        )}
        {selectedTool && (
          <ToolDetailPanel
            key={toolRefKey(selectedTool.tool)}
            scope={scope}
            tool={selectedTool}
            disabled={disabled}
            configurationError={command.error?.startsWith('配置不符合')}
            onDirty={setDirty}
            onPrepare={(configuration) =>
              command.start(
                'prepareDefaultBinding',
                defaultBindingRequest(scope, selectedTool.tool, configuration),
              )
            }
          />
        )}
        {selectedBinding && (
          <BindingEditor
            key={`${selectedBinding.bindingId}:${bindingRevision}`}
            scope={scope}
            binding={selectedBinding}
            tools={catalog.records}
            disabled={disabled}
            onDirty={setDirty}
            onWrite={command.start}
          />
        )}
      </Drawer>
    </section>
  );
}
