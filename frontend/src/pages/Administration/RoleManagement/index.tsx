import { message } from 'antd';
import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type ActionButton, SimpleTable, type TableColumn } from '@/components';
import PageWrapper from '@/components/PageWrapper';
import SearchForm from '@/components/SearchForm';
import type { SearchFieldConfig } from '@/components/SearchForm/SearchFormTypes';
import { listRole } from '@/services/ant-design-pro/rbac';
import AssignMenusModal from '../MenuManagement/assignMenusModal';
import AssignOperationsModal from '../MenuOperationManagement/assignOperationsModal';
import AssignUsersModal from './assignUsersModal';

const columns: TableColumn[] = [
  {
    title: '角色编码',
    dataIndex: 'roleCode',
    width: 100,
    sorter: true,
    order: 1,
  },
  {
    title: '角色名',
    dataIndex: 'roleName',
    width: 100,
    sorter: true,
    order: 2,
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    sorter: true,
    order: 3,
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
    title: '描述',
    dataIndex: 'description',
    width: 100,
    sorter: true,
    order: 4,
  },
  {
    title: '创建人',
    dataIndex: 'createBy',
    width: 100,
    sorter: true,
    order: 5,
  },
  {
    title: '更新人',
    dataIndex: 'updateBy',
    width: 100,
    sorter: true,
    order: 6,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 100,
    sorter: true,
    order: 7,
  },
  {
    title: '更新时间',
    dataIndex: 'updateTime',
    width: 100,
    sorter: true,
    order: 8,
  },
];

const searchFields: SearchFieldConfig[] = [
  {
    fieldName: 'roleCode',
    fieldType: 'input',
    label: '角色编码',
    placeholder: '请输入角色编码',
    alwaysShow: true,
  },
  {
    fieldName: 'roleName',
    fieldType: 'input',
    label: '角色名',
    placeholder: '请输入角色名',
    alwaysShow: true,
  },
  {
    fieldName: 'status',
    fieldType: 'select',
    label: '状态',
    options: [
      { label: '初始', value: 0 },
      { label: '正常', value: 1 },
      { label: '完成', value: 2 },
      { label: '注销', value: 3 },
    ],
    alwaysShow: true,
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

export default function UserPage() {
  const tableRef = useRef<any>(null);
  const navigate = useNavigate();
  // 添加权限分配弹窗状态管理
  const [operationModalVisible, setOperationModalVisible] = useState(false);
  const [selectedRoleCode, setSelectedRoleCode] = useState<string>('');
  // 添加用户分配弹窗状态管理
  const [userModalVisible, setUserModalVisible] = useState(false);
  // 添加菜单分配弹窗状态管理
  const [menuModalVisible, setMenuModalVisible] = useState(false);

  const fetchTableData = async (params: any) => {
    return await listRole(params);
  };

  const handleReset = (values: Record<string, any>) => {
    console.log('重置后的值:', values);
  };

  // 操作按钮
  const actionButtons: ActionButton[] = [
    {
      text: '新增',
      authority: 'role:add',
      handler: () =>
        navigate('/Administration/RoleManagement/RoleForm?mode=create'),
    },
    {
      text: '编辑',
      authority: 'role:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        navigate(
          `/Administration/RoleManagement/RoleForm?mode=edit&id=${records[0].id}&roleCode=${records[0].roleCode}`,
        );
      },
    },
    {
      text: '注销',
      authority: 'role:update',
      handler: () => message.info('注销角色操作'),
    },
    {
      text: '分配用户',
      authority: 'role:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedRoleCode(records[0].roleCode);
        setUserModalVisible(true);
      },
    },
    {
      text: '分配菜单',
      authority: 'role:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedRoleCode(records[0].roleCode);
        setMenuModalVisible(true);
      },
    },
    {
      text: '分配权限',
      authority: 'role:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedRoleCode(records[0].roleCode);
        setOperationModalVisible(true);
      },
    },
    {
      text: '导出',
      authority: 'role:export',
      handler: () => message.info('导出角色操作'),
    },
    {
      text: '导出全部',
      authority: 'role:export',
      handler: () => message.info('导出全部角色操作'),
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
              `/Administration/RoleManagement/RoleForm?mode=view&id=${record.id}&roleCode=${record.roleCode}`,
            );
          }}
        />
        {/* 添加权限分配弹窗 */}
        <AssignOperationsModal
          visible={operationModalVisible}
          sourceName={selectedRoleCode}
          sourceType="role"
          onCancel={() => setOperationModalVisible(false)}
          onSuccess={() => {
            setOperationModalVisible(false);
            // 刷新角色列表
            tableRef.current?.query();
          }}
        />
        {/* 添加用户分配弹窗 */}
        <AssignUsersModal
          visible={userModalVisible}
          roleCode={selectedRoleCode}
          onCancel={() => setUserModalVisible(false)}
          onSuccess={() => {
            setUserModalVisible(false);
            // 刷新角色列表
            tableRef.current?.query();
          }}
        />
        {/* 添加菜单分配弹窗 */}
        <AssignMenusModal
          visible={menuModalVisible}
          sourceName={selectedRoleCode}
          sourceType="role"
          onCancel={() => setMenuModalVisible(false)}
          onSuccess={() => {
            setMenuModalVisible(false);
            // 刷新角色列表
            tableRef.current?.query();
          }}
        />
      </div>
    </PageWrapper>
  );
}
