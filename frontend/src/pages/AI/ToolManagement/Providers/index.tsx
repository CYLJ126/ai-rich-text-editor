import {CheckCircleOutlined, CloseCircleOutlined, SyncOutlined,} from '@ant-design/icons';
import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {useAccess} from '@umijs/max';
import {Button, Descriptions, Drawer, Input, message, Modal, Popconfirm, Space, Tag, Tooltip, Typography,} from 'antd';
import dayjs from 'dayjs';
import React, {useRef, useState} from 'react';
import {normalizeToolError} from '@/features/ai-tool';
import {
  disableToolProvider,
  enableToolProvider,
  listToolProviders,
  synchronizeAllToolProviders,
  synchronizeToolProvider,
} from '@/services/ant-design-pro/ai.tool';
import type {ToolProviderSyncResult, ToolProviderView,} from '@/types/ai.tool.type';
import {ProviderStatusTag, ToolManagementPage} from '../components';

export default function ToolProvidersPage() {
  const access = useAccess();
  const actionRef = useRef<ActionType>(null);
  const [detail, setDetail] = useState<ToolProviderView>();
  const [syncingAll, setSyncingAll] = useState(false);
  const [syncingProviderId, setSyncingProviderId] = useState<string>();
  const [disableTarget, setDisableTarget] = useState<ToolProviderView>();
  const [disableReason, setDisableReason] = useState('');
  const [disabling, setDisabling] = useState(false);

  const reload = () => actionRef.current?.reload();

  const handleSync = async (record: ToolProviderView) => {
    setSyncingProviderId(record.providerId);
    try {
      const result = await synchronizeToolProvider(record.providerId);
      showSyncResult(result);
      reload();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setSyncingProviderId(undefined);
    }
  };

  const handleSyncAll = async () => {
    setSyncingAll(true);
    try {
      const results = await synchronizeAllToolProviders();
      const failed = results.filter((item) => !item.succeeded);
      if (failed.length === 0) {
        message.success(`已同步 ${results.length} 个工具提供者`).then();
      } else {
        Modal.warning({
          title: '部分提供者同步失败',
          content: failed
            .map((item) => `${item.providerId}: ${item.errorMessage}`)
            .join('\n'),
        });
      }
      reload();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setSyncingAll(false);
    }
  };

  const handleEnable = async (record: ToolProviderView) => {
    try {
      await enableToolProvider(record.providerId);
      message.success('提供者已启用，后台正在同步').then();
      reload();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    }
  };

  const handleDisable = async () => {
    if (!disableTarget) return;
    setDisabling(true);
    try {
      await disableToolProvider(
        disableTarget.providerId,
        disableReason.trim() || undefined,
      );
      message.success('提供者已禁用').then();
      setDisableTarget(undefined);
      setDisableReason('');
      reload();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setDisabling(false);
    }
  };

  const columns: ProColumns<ToolProviderView>[] = [
    {
      title: '提供者',
      dataIndex: 'name',
      ellipsis: true,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Link onClick={() => setDetail(record)}>
            {record.name}
          </Typography.Link>
          <Typography.Text type="secondary" copyable>
            {record.providerId}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'providerType',
      width: 120,
      valueType: 'select',
      valueEnum: {
        local: {text: '本地 Java'},
        http: {text: 'HTTP'},
        openapi: {text: 'OpenAPI'},
        mcp: {text: 'MCP'},
        agent: {text: 'Agent'},
      },
      render: (_, record) => <Tag>{record.providerType.toUpperCase()}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      valueEnum: {
        enabled: {text: '已启用'},
        disabled: {text: '已禁用'},
        'not-synchronized': {text: '未同步'},
      },
      render: (_, record) => <ProviderStatusTag status={record.status}/>,
    },
    {
      title: '运行时',
      dataIndex: 'loaded',
      width: 100,
      search: false,
      render: (_, record) =>
        record.loaded ? (
          <Tag icon={<CheckCircleOutlined/>} color="success">
            已加载
          </Tag>
        ) : (
          <Tag icon={<CloseCircleOutlined/>}>未加载</Tag>
        ),
    },
    {
      title: '工具数',
      dataIndex: 'toolCount',
      width: 90,
      search: false,
    },
    {
      title: '最后同步',
      dataIndex: 'lastSyncTime',
      width: 180,
      search: false,
      renderText: (value) =>
        value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '从未同步',
    },
    {
      title: '同步状态',
      key: 'syncStatus',
      width: 120,
      search: false,
      render: (_, record) =>
        record.lastError ? (
          <Tooltip title={record.lastError}>
            <Tag color="error">同步失败</Tag>
          </Tooltip>
        ) : (
          <Tag color="success">正常</Tag>
        ),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 220,
      fixed: 'right',
      render: (_, record) => [
        <Typography.Link key="detail" onClick={() => setDetail(record)}>
          详情
        </Typography.Link>,
        access.canManageAiTools &&
        record.loaded &&
        record.status !== 'disabled' ? (
          <Typography.Link
            key="sync"
            onClick={() => handleSync(record)}
            disabled={syncingProviderId === record.providerId}
          >
            刷新
          </Typography.Link>
        ) : null,
        access.canManageAiTools &&
        record.loaded &&
        record.status === 'disabled' ? (
          <Popconfirm
            key="enable"
            title="确认启用此提供者？"
            onConfirm={() => handleEnable(record)}
          >
            <Typography.Link>启用</Typography.Link>
          </Popconfirm>
        ) : null,
        access.canManageAiTools &&
        record.loaded &&
        record.status === 'enabled' ? (
          <Typography.Link
            key="disable"
            type="danger"
            onClick={() => setDisableTarget(record)}
          >
            禁用
          </Typography.Link>
        ) : null,
      ],
    },
  ];

  return (
    <ToolManagementPage
      activeKey="providers"
      title="工具提供者"
      subTitle="管理本地 Java、HTTP、OpenAPI、MCP 和 Agent 工具来源"
      extra={
        access.canManageAiTools ? (
          <Button
            icon={<SyncOutlined/>}
            loading={syncingAll}
            onClick={handleSyncAll}
          >
            刷新全部
          </Button>
        ) : undefined
      }
    >
      <ProTable<ToolProviderView>
        rowKey="providerId"
        actionRef={actionRef}
        columns={columns}
        scroll={{x: 1200}}
        pagination={false}
        request={async (params) => {
          try {
            const records = await listToolProviders({
              status: params.status as string | undefined,
              providerType: params.providerType as
                | ToolProviderView['providerType']
                | undefined,
            });
            return {data: records, total: records.length, success: true};
          } catch (error) {
            message.error(normalizeToolError(error).message).then();
            return {data: [], total: 0, success: false};
          }
        }}
        search={{labelWidth: 'auto'}}
        options={{reload: true, density: true, setting: true}}
      />

      <Drawer
        title="提供者详情"
        width={640}
        open={Boolean(detail)}
        onClose={() => setDetail(undefined)}
      >
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="提供者 ID">
              {detail.providerId}
            </Descriptions.Item>
            <Descriptions.Item label="名称">{detail.name}</Descriptions.Item>
            <Descriptions.Item label="类型">
              {detail.providerType}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <ProviderStatusTag status={detail.status}/>
            </Descriptions.Item>
            <Descriptions.Item label="端点">
              {detail.endpoint || '本地提供者'}
            </Descriptions.Item>
            <Descriptions.Item label="启动自动刷新">
              {detail.supportsStartupRefresh ? '是' : '否'}
            </Descriptions.Item>
            <Descriptions.Item label="非敏感配置">
              <Typography.Text code>
                {JSON.stringify(detail.configuration)}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="最后错误">
              {detail.lastError || '无'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>

      <Modal
        title={`禁用提供者：${disableTarget?.name || ''}`}
        open={Boolean(disableTarget)}
        confirmLoading={disabling}
        okButtonProps={{danger: true}}
        okText="确认禁用"
        onOk={handleDisable}
        onCancel={() => {
          setDisableTarget(undefined);
          setDisableReason('');
        }}
      >
        <Typography.Paragraph type="secondary">
          禁用后该提供者的工具将从当前节点和集群注册表中移除。
        </Typography.Paragraph>
        <Input.TextArea
          value={disableReason}
          onChange={(event) => setDisableReason(event.target.value)}
          placeholder="请输入禁用原因（可选）"
          maxLength={500}
          showCount
        />
      </Modal>
    </ToolManagementPage>
  );
}

function showSyncResult(result: ToolProviderSyncResult) {
  if (!result.succeeded) {
    Modal.error({
      title: '同步失败',
      content: result.errorMessage || '未知错误',
    });
    return;
  }
  message
    .success(
      `同步完成：发现 ${result.discoveredCount}，新增 ${result.createdCount}，更新 ${result.updatedCount}`,
    )
    .then();
}
