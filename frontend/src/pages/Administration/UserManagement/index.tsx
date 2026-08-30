import { Modal, message } from 'antd';
import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type ActionButton, SimpleTable, type TableColumn } from '@/components';
import PageWrapper from '@/components/PageWrapper';
import SearchForm from '@/components/SearchForm';
import type { SearchFieldConfig } from '@/components/SearchForm/SearchFormTypes';
import { deactivateUser, listUser } from '@/services/ant-design-pro/rbac';
import AssignMenusModal from '../MenuManagement/assignMenusModal';
import AssignOperationsModal from '../MenuOperationManagement/assignOperationsModal';
import AssignRoleModal from './assignRoleModal';

const columns: TableColumn[] = [
  {
    title: '用户名',
    dataIndex: 'userName',
    width: 100,
    sorter: true,
    order: 1,
  },
  {
    title: '手机号',
    dataIndex: 'mobile',
    width: 100,
    sorter: true,
    order: 2,
  },
  {
    title: '邮箱',
    dataIndex: 'email',
    width: 100,
    sorter: true,
    order: 3,
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    sorter: true,
    order: 4,
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
    order: 9,
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
    fieldName: 'name',
    fieldType: 'input',
    label: '用户名',
    placeholder: '请输入用户名',
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
  },
  {
    fieldName: 'mobile',
    fieldType: 'input',
    label: '手机号',
    placeholder: '请输入手机号',
    alwaysShow: true,
  },
  {
    fieldName: 'email',
    fieldType: 'input',
    label: '邮箱',
    placeholder: '请输入邮箱',
    alwaysShow: true,
  },
  {
    fieldName: 'createBy',
    fieldType: 'input',
    label: '创建人',
    placeholder: '请输入创建人',
    alwaysShow: true,
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
  // 分配角色弹窗状态
  const [roleModalVisible, setRoleModalVisible] = useState(false);
  // 分配权限弹窗状态
  const [operationModalVisible, setOperationModalVisible] = useState(false);
  // 分配菜单弹窗状态
  const [menuModalVisible, setMenuModalVisible] = useState(false);
  const [selectedUserName, setSelectedUserName] = useState<string>('');

  const fetchTableData = async (params: any) => {
    return await listUser(params);
  };

  const handleReset = (values: Record<string, any>) => {
    console.log('重置后的值:', values);
  };

  // 操作按钮
  const actionButtons: ActionButton[] = [
    {
      text: '新增',
      authority: 'user:add',
      handler: () =>
        navigate('/Administration/UserManagement/UserForm?mode=create'),
    },
    {
      text: '编辑',
      authority: 'user:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        navigate(
          `/Administration/UserManagement/UserForm?mode=edit&id=${records[0].id}&userName=${records[0].userName}`,
        );
      },
    },
    {
      text: '注销',
      authority: 'user:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        const userName = records[0].userName;
        Modal.confirm({
          title: '确认注销',
          content: `确定要注销用户 "${userName}" 吗？此操作不可撤销。`,
          okText: '确认',
          cancelText: '取消',
          onOk: async () => {
            try {
              deactivateUser(userName).then((result) => {
                if (result) {
                  message.success('用户注销成功');
                  // 刷新表格数据
                  tableRef.current?.query();
                }
              });
            } catch (error) {
              message.error('用户注销失败，请重试');
              console.error('注销用户失败：', error);
            }
          },
        });
      },
    },
    {
      text: '分配角色',
      authority: 'user:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedUserName(records[0].userName);
        setRoleModalVisible(true);
      },
    },
    {
      text: '分配菜单',
      authority: 'user:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedUserName(records[0].userName);
        setMenuModalVisible(true);
      },
    },
    {
      text: '分配权限',
      authority: 'user:update',
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning('请选择且只选择一条记录').then();
          return;
        }
        setSelectedUserName(records[0].userName);
        setOperationModalVisible(true);
      },
    },
    {
      text: '导出',
      authority: 'user:export',
      handler: () => message.info('导出用户操作'),
    },
    {
      text: '导出全部',
      authority: 'user:export',
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
              `/Administration/UserManagement/UserForm?mode=view&id=${record.id}&userName=${record.userName}`,
            );
          }}
        />
        {/* 分配角色弹窗 */}
        <AssignRoleModal
          visible={roleModalVisible}
          userName={selectedUserName}
          onCancel={() => setRoleModalVisible(false)}
          onSuccess={() => {
            setRoleModalVisible(false);
            // 刷新用户列表
            tableRef.current?.query();
          }}
        />
        {/* 分配权限弹窗 */}
        <AssignOperationsModal
          visible={operationModalVisible}
          sourceName={selectedUserName}
          sourceType="user"
          onCancel={() => setOperationModalVisible(false)}
          onSuccess={() => {
            setOperationModalVisible(false);
            // 刷新用户列表
            tableRef.current?.query();
          }}
        />
        {/* 分配菜单弹窗 */}
        <AssignMenusModal
          visible={menuModalVisible}
          sourceName={selectedUserName}
          sourceType="user"
          onCancel={() => setMenuModalVisible(false)}
          onSuccess={() => {
            setMenuModalVisible(false);
            // 刷新用户列表
            tableRef.current?.query();
          }}
        />
      </div>
    </PageWrapper>
  );
}
