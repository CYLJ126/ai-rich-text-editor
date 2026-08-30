import { message } from 'antd';
import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type ActionButton, SimpleTable, type TableColumn } from '@/components';
import PageWrapper from '@/components/PageWrapper';
import SearchForm from '@/components/SearchForm';
import type { SearchFieldConfig } from '@/components/SearchForm/SearchFormTypes';
import { listMenu } from '@/services/ant-design-pro/rbac';
import ManageOperationsModal from '../MenuOperationManagement/manageOperationsModal';

const columns: TableColumn[] = [
  {
    title: '菜单编码',
    dataIndex: 'menuCode',
    width: 100,
    sorter: true,
    order: 1,
  },
  {
    title: '菜单名称',
    dataIndex: 'menuName',
    width: 100,
    sorter: true,
    order: 2,
  },
  {
    title: '上级菜单 ID',
    dataIndex: 'fatherId',
    width: 100,
    sorter: true,
    order: 3,
  },
  {
    title: '顺序',
    dataIndex: 'orderId',
    width: 50,
    sorter: true,
    order: 4,
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 50,
    sorter: true,
    order: 5,
    render: (text: number) => {
      if (text === 0) {
        return (
          <span
            style={{
              backgroundColor: '#f0f9eb',
              color: '#586163',
              padding: '2px 8px',
              borderRadius: '4px',
            }}
          >
            初始
          </span>
        );
      } else if (text === 1) {
        return (
          <span
            style={{
              backgroundColor: '#f0f9eb',
              color: '#52c41a',
              padding: '2px 8px',
              borderRadius: '4px',
            }}
          >
            正常
          </span>
        );
      } else if (text === 2) {
        return (
          <span
            style={{
              backgroundColor: '#f0f9eb',
              color: '#52c41a',
              padding: '2px 8px',
              borderRadius: '4px',
            }}
          >
            完成
          </span>
        );
      } else if (text === 3) {
        return (
          <span
            style={{
              backgroundColor: '#f0f9eb',
              color: '#c48b1a',
              padding: '2px 8px',
              borderRadius: '4px',
            }}
          >
            注销
          </span>
        );
      }
      return (
        <span
          style={{
            backgroundColor: '#f6ffed',
            color: '#ff4d4f',
            padding: '2px 8px',
            borderRadius: '4px',
          }}
        >
          text
        </span>
      );
    },
  },
  {
    title: '菜单地址',
    dataIndex: 'menuUrl',
    width: 150,
    sorter: true,
    order: 6,
  },
  {
    title: '描述',
    dataIndex: 'description',
    width: 100,
    sorter: true,
    order: 7,
  },
  {
    title: '创建人',
    dataIndex: 'createBy',
    width: 100,
    sorter: true,
    order: 8,
  },
  {
    title: '更新人',
    dataIndex: 'updateBy',
    width: 100,
    sorter: true,
    order: 9,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 100,
    sorter: true,
    order: 10,
  },
  {
    title: '更新时间',
    dataIndex: 'updateTime',
    width: 100,
    sorter: true,
    order: 11,
  },
];

const searchFields: SearchFieldConfig[] = [
  {
    fieldName: 'menuCode',
    fieldType: 'input',
    label: '菜单编码',
    placeholder: '请输入菜单编码',
    alwaysShow: true,
  },
  {
    fieldName: 'menuName',
    fieldType: 'input',
    label: '菜单名称',
    placeholder: '请输入菜单名称',
    alwaysShow: true,
  },
  {
    fieldName: 'fatherId',
    fieldType: 'input',
    label: '上级菜单 ID',
    placeholder: '请输入上级菜单 ID',
  },
  {
    fieldName: 'status',
    fieldType: 'select',
    label: '状态',
    alwaysShow: true,
    options: [
      { label: '初始', value: 0 },
      { label: '正常', value: 1 },
      { label: '完成', value: 2 },
      { label: '注销', value: 3 },
    ],
  },
  {
    fieldName: 'menuUrl',
    fieldType: 'input',
    label: '菜单地址',
    placeholder: '请输入菜单地址',
  },
  {
    fieldName: 'createBy',
    fieldType: 'input',
    label: '创建人',
    placeholder: '请输入创建人',
  },
  {
    fieldName: 'createTime',
    fieldType: 'dateRangePicker',
    label: '创建时间',
    placeholder: '请选择创建时间',
    format: 'YYYY-MM-DD HH:mm:ss',
    transformFunction: (value) => {
      if (value && value.length === 2) {
        return {
          createTimeFloor: value[0],
          createTimeCeil: value[1],
        };
      }
      return {};
    },
  },
];

export default function MenuPage() {
  const tableRef = useRef<any>(null);
  const navigate = useNavigate();
  const [operationModalVisible, setOperationModalVisible] = useState(false);
  const [selectedMenu, setSelectedMenu] = useState({ code: '', name: '' });

  const fetchTableData = async (params: any) => {
    return await listMenu(params);
  };

  const handleReset = (values: Record<string, any>) => {
    console.log('重置后的值:', values);
  };

  // 操作按钮
  const actionButtons: ActionButton[] = [
    {
      text: '新增',
      authority: 'menu:add',
      handler: () =>
        navigate('/Administration/MenuManagement/MenuForm?mode=create'),
    },
    {
      text: '编辑',
      authority: 'menu:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        navigate(
          `/Administration/MenuManagement/MenuForm?mode=edit&id=${records[0].id}&menuCode=${records[0].menuCode}`,
        );
      },
    },
    {
      text: '管理操作权限',
      authority: 'menu:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedMenu({
          code: records[0].menuCode,
          name: records[0].menuName,
        });
        setOperationModalVisible(true);
      },
    },
    {
      text: '删除',
      authority: 'menu:update',
      handler: () => message.info('删除用户操作'),
    },
    {
      text: '导出',
      authority: 'menu:export',
      handler: () => message.info('导出用户操作'),
    },
    {
      text: '导出全部',
      authority: 'menu:export',
      handler: () => message.info('导出全部用户操作'),
    },
  ];

  return (
    <PageWrapper>
      <div style={{ padding: 5 }}>
        <SearchForm
          gutter={[8, 8]}
          fields={searchFields}
          size="middle"
          onSearch={(formData) => {
            tableRef.current.query(formData);
          }}
          onReset={handleReset}
          collapsible={true}
          defaultCollapsed={false}
          collapsedRows={1}
          searchShortcut="Enter"
          resetShortcut="r"
        />
        <SimpleTable
          ref={tableRef}
          columns={columns}
          fetchData={fetchTableData}
          tableHeight={760}
          actionButtons={actionButtons}
          initialParams={{ status: 'active' }}
          defaultPageSize={20}
          doubleClick={(record: any) => {
            navigate(
              `/Administration/MenuManagement/MenuForm?mode=view&id=${record.id}&menuCode=${record.menuCode}`,
            );
          }}
        />
        <ManageOperationsModal
          visible={operationModalVisible}
          menuCode={selectedMenu.code}
          menuName={selectedMenu.name}
          onClose={() => setOperationModalVisible(false)}
        />
      </div>
    </PageWrapper>
  );
}
