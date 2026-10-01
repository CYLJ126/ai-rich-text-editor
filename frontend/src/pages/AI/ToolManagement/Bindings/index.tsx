import {PlusOutlined} from '@ant-design/icons';
import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {useAccess} from '@umijs/max';
import {Button, message, Popconfirm, Space, Tag, Typography} from 'antd';
import React, {useRef, useState} from 'react';
import {normalizeToolError, toolReferenceLabel} from '@/features/ai-tool';
import {listToolBindings, saveToolBinding,} from '@/services/ant-design-pro/ai.tool';
import type {ToolBindingView} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';
import BindingEditor from './BindingEditor';

export default function ToolBindingsPage() {
  const access = useAccess();
  const actionRef = useRef<ActionType>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<ToolBindingView>();
  const [workspaceId, setWorkspaceId] = useState<string>();
  const [updatingId, setUpdatingId] = useState<string>();

  const openEditor = (binding?: ToolBindingView) => {
    setEditing(binding);
    setEditorOpen(true);
  };

  const toggleBinding = async (binding: ToolBindingView) => {
    setUpdatingId(binding.bindingId);
    try {
      await saveToolBinding({
        bindingId: binding.bindingId,
        workspaceId: binding.workspaceId,
        tool: binding.baselineTool || binding.tool,
        versionPolicy: binding.versionPolicy,
        credentialReference: binding.credentialReference,
        configuration: binding.configuration,
        policyOverride: binding.policyOverride,
        enabled: !binding.enabled,
        expectedRowVersion: binding.rowVersion,
      });
      message.success(binding.enabled ? '绑定已禁用' : '绑定已启用').then();
      actionRef.current?.reload();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setUpdatingId(undefined);
    }
  };

  const columns: ProColumns<ToolBindingView>[] = [
    {
      title: '工作空间 ID',
      dataIndex: 'workspaceId',
      hideInTable: true,
      fieldProps: {allowClear: true, placeholder: '留空查询个人作用域'},
    },
    {
      title: '工具 / 当前版本',
      key: 'tool',
      search: false,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>
            {record.tool.namespace}.{record.tool.name}
          </Typography.Text>
          <Typography.Text type="secondary">
            当前 {record.tool.version} · 基准 {record.baselineTool?.version || record.tool.version}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '升级方式',
      key: 'versionPolicy',
      search: false,
      render: (_, record) => <Tag>{record.versionPolicy === 'pinned' ? '锁定版本' : '跟随兼容升级'}</Tag>,
    },
    {
      title: '更新状态',
      key: 'updateStatus',
      search: false,
      render: (_, record) => <Space direction="vertical" size={0}>
        <Tag color={record.updateStatus === 'requires-review' ? 'warning' : 'default'}>
          {record.updateStatus === 'requires-review' ? `有新版本 ${record.latestVersion}`
            : record.updateStatus === 'auto-upgraded' ? '已兼容升级'
              : record.updateStatus === 'unavailable' ? '暂不可用' : '已是最新'}
        </Tag>
        {record.releaseNotes && <Typography.Text type="secondary" ellipsis={{tooltip: record.releaseNotes}}
                                                 style={{maxWidth: 220}}>{record.releaseNotes}</Typography.Text>}
      </Space>,
    },
    {
      title: '作用域',
      key: 'scope',
      width: 180,
      search: false,
      render: (_, record) =>
        record.workspaceId ? (
          <Tag color="blue">{record.workspaceId}</Tag>
        ) : (
          <Tag>个人</Tag>
        ),
    },
    {
      title: '绑定状态',
      dataIndex: 'enabled',
      width: 110,
      search: false,
      render: (_, record) =>
        record.enabled ? (
          <Tag color="success">已启用</Tag>
        ) : (
          <Tag color="default">已禁用</Tag>
        ),
    },
    {
      title: '可用性',
      dataIndex: 'available',
      width: 110,
      search: false,
      render: (_, record) =>
        record.available ? (
          <Tag color="success">可调用</Tag>
        ) : (
          <Tag color="error">不可用</Tag>
        ),
    },
    {
      title: '凭据引用',
      dataIndex: 'credentialReference',
      search: false,
      ellipsis: true,
      renderText: (value) => value || '继承提供者凭据',
    },
    {
      title: '绑定 ID',
      dataIndex: 'bindingId',
      search: false,
      ellipsis: true,
      render: (_, record) => (
        <Typography.Text copyable>{record.bindingId}</Typography.Text>
      ),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 150,
      render: (_, record) => [
        <Typography.Link
          key="edit"
          disabled={!access.canConfigureAiTools}
          onClick={() => openEditor(record)}
        >
          {record.updateStatus === 'requires-review' ? '配置 / 查看更新' : '配置'}
        </Typography.Link>,
        access.canConfigureAiTools ? (
          <Popconfirm
            key="toggle"
            title={`确认${record.enabled ? '禁用' : '启用'} ${toolReferenceLabel(record.tool)}？`}
            onConfirm={() => toggleBinding(record)}
          >
            <Typography.Link
              disabled={updatingId === record.bindingId}
              type={record.enabled ? 'danger' : undefined}
            >
              {record.enabled ? '禁用' : '启用'}
            </Typography.Link>
          </Popconfirm>
        ) : null,
      ],
    },
  ];

  return (
    <ToolManagementPage
      activeKey="bindings"
      title="用户工具绑定"
      subTitle="兼容升级保留配置和助手关联，也可选择锁定版本"
      extra={
        access.canConfigureAiTools ? (
          <Button
            type="primary"
            icon={<PlusOutlined/>}
            onClick={() => openEditor()}
          >
            新建绑定
          </Button>
        ) : undefined
      }
    >
      <ProTable<ToolBindingView>
        rowKey="bindingId"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        request={async (params) => {
          const scope =
            typeof params.workspaceId === 'string'
              ? params.workspaceId.trim() || undefined
              : undefined;
          setWorkspaceId(scope);
          try {
            const records = await listToolBindings(scope);
            return {data: records, total: records.length, success: true};
          } catch (error) {
            message.error(normalizeToolError(error).message).then();
            return {data: [], total: 0, success: false};
          }
        }}
        search={{labelWidth: 'auto'}}
        options={{reload: true, density: true, setting: true}}
      />
      <BindingEditor
        open={editorOpen}
        binding={editing}
        defaultWorkspaceId={workspaceId}
        onClose={() => {
          setEditorOpen(false);
          setEditing(undefined);
        }}
        onSaved={() => actionRef.current?.reload()}
      />
    </ToolManagementPage>
  );
}
