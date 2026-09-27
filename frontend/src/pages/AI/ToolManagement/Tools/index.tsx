import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {useNavigate} from '@umijs/max';
import {message, Space, Tag, Typography} from 'antd';
import dayjs from 'dayjs';
import React, {useEffect, useRef, useState} from 'react';
import {ToolStatusTag} from '@/components/AITool';
import {normalizeToolError} from '@/features/ai-tool';
import {listToolCatalog, listToolProviders,} from '@/services/ant-design-pro/ai.tool';
import type {ToolCatalogItem, ToolLifecycleState, ToolProviderView,} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';

export default function ToolCatalogPage() {
  const navigate = useNavigate();
  const actionRef = useRef<ActionType>(null);
  const [providers, setProviders] = useState<ToolProviderView[]>([]);

  useEffect(() => {
    listToolProviders({})
      .then(setProviders)
      .catch(() => setProviders([]));
  }, []);

  const providerValueEnum = Object.fromEntries(
    providers.map((provider) => [provider.providerId, {text: provider.name}]),
  );
  const openDetail = (record: ToolCatalogItem) => {
    if (!record.latestVersion) {
      message.warning('该工具尚无可查看的版本').then();
      return;
    }
    const params = new URLSearchParams({
      namespace: record.namespace,
      name: record.name,
      version: record.latestVersion,
    });
    navigate(`/AI/ToolManagement/Tools/Detail?${params.toString()}`);
  };

  const columns: ProColumns<ToolCatalogItem>[] = [
    {
      title: '关键词',
      dataIndex: 'keyword',
      hideInTable: true,
      fieldProps: {placeholder: '名称、命名空间或描述'},
    },
    {
      title: '工具',
      dataIndex: 'title',
      search: false,
      ellipsis: true,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Link onClick={() => openDetail(record)}>
            {record.title}
          </Typography.Link>
          <Typography.Text type="secondary" copyable>
            {record.namespace}.{record.name}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '提供者',
      dataIndex: 'providerId',
      valueType: 'select',
      valueEnum: providerValueEnum,
      width: 150,
      render: (_, record) =>
        providers.find((item) => item.providerId === record.providerId)?.name ||
        record.providerId,
    },
    {
      title: '当前版本',
      dataIndex: 'latestVersion',
      width: 120,
      search: false,
      render: (_, record) =>
        record.latestVersion ? <Tag>{record.latestVersion}</Tag> : '-',
    },
    {
      title: '生命周期',
      dataIndex: 'lifecycleState',
      width: 120,
      valueType: 'select',
      valueEnum: {
        draft: {text: '草稿'},
        published: {text: '已发布'},
        deprecated: {text: '已废弃'},
        disabled: {text: '已禁用'},
      },
      render: (_, record) => <ToolStatusTag status={record.lifecycleState}/>,
    },
    {
      title: '版本数',
      dataIndex: 'versionCount',
      width: 90,
      search: false,
    },
    {
      title: '描述',
      dataIndex: 'description',
      search: false,
      ellipsis: true,
    },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      width: 180,
      search: false,
      renderText: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 90,
      render: (_, record) => (
        <Typography.Link onClick={() => openDetail(record)}>
          详情
        </Typography.Link>
      ),
    },
  ];

  return (
    <ToolManagementPage
      activeKey="catalog"
      title="工具目录"
      subTitle="查看全部管理态工具；模型调用仍只暴露已发布且可用的固定版本"
    >
      <ProTable<ToolCatalogItem>
        rowKey="toolId"
        actionRef={actionRef}
        columns={columns}
        scroll={{x: 1200}}
        pagination={{defaultPageSize: 20, showSizeChanger: true}}
        request={async (params) => {
          try {
            const page = await listToolCatalog({
              current: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword as string | undefined,
              providerId: params.providerId as string | undefined,
              lifecycleState: params.lifecycleState as
                | ToolLifecycleState
                | undefined,
            });
            return {data: page.records, total: page.total, success: true};
          } catch (error) {
            message.error(normalizeToolError(error).message).then();
            return {data: [], total: 0, success: false};
          }
        }}
        search={{labelWidth: 'auto'}}
        options={{reload: true, density: true, setting: true}}
      />
    </ToolManagementPage>
  );
}
