import {i18nText} from '@/utils/i18n';
import { message, Modal } from 'antd';
import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type ActionButton, SimpleTable, type TableColumn } from '@/components';
import PageWrapper from '@/components/PageWrapper';
import SearchForm from '@/components/SearchForm';
import type { SearchFieldConfig } from '@/components/SearchForm/SearchFormTypes';
import { deactivateRole, listRole } from '@/services/ant-design-pro/rbac';
import {downloadCsv, fetchAllPages} from '@/utils/tableExport';
import AssignMenusModal from '../MenuManagement/assignMenusModal';
import AssignOperationsModal from '../MenuOperationManagement/assignOperationsModal';
import AssignUsersModal from './assignUsersModal';

const columns: TableColumn[] = [
  {
    title: i18nText("app.administration.rolemanagement.a40a406e"),
    dataIndex: 'roleCode',
    width: 100,
    sorter: true,
    order: 1,
  },
  {
    title: i18nText("app.administration.rolemanagement.848cca2d"),
    dataIndex: 'roleName',
    width: 100,
    sorter: true,
    order: 2,
  },
  {
    title: i18nText("app.administration.rolemanagement.5daf81c0"),
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
            {i18nText("app.administration.rolemanagement.bca8a187")}
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
            {i18nText("app.administration.rolemanagement.6434eed0")}
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
            {i18nText("app.administration.rolemanagement.3a3a05a2")}
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
            {i18nText("app.administration.rolemanagement.c4241368")}
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
    title: i18nText("app.administration.rolemanagement.97e4e26c"),
    dataIndex: 'description',
    width: 100,
    sorter: true,
    order: 4,
  },
  {
    title: i18nText("app.administration.rolemanagement.4a4d61a5"),
    dataIndex: 'createBy',
    width: 100,
    sorter: true,
    order: 5,
  },
  {
    title: i18nText("app.administration.rolemanagement.e072be94"),
    dataIndex: 'updateBy',
    width: 100,
    sorter: true,
    order: 6,
  },
  {
    title: i18nText("app.administration.rolemanagement.1e1b108c"),
    dataIndex: 'createTime',
    width: 100,
    sorter: true,
    order: 7,
  },
  {
    title: i18nText("app.administration.rolemanagement.c456f9fb"),
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
    label: i18nText("app.administration.rolemanagement.a40a406e"),
    placeholder: i18nText("app.administration.rolemanagement.66fe4023"),
    alwaysShow: true,
  },
  {
    fieldName: 'roleName',
    fieldType: 'input',
    label: i18nText("app.administration.rolemanagement.848cca2d"),
    placeholder: i18nText("app.administration.rolemanagement.d982c119"),
    alwaysShow: true,
  },
  {
    fieldName: 'status',
    fieldType: 'select',
    label: i18nText("app.administration.rolemanagement.5daf81c0"),
    options: [
      { label: i18nText("app.administration.rolemanagement.bca8a187"), value: 0 },
      { label: i18nText("app.administration.rolemanagement.6434eed0"), value: 1 },
      { label: i18nText("app.administration.rolemanagement.3a3a05a2"), value: 2 },
      { label: i18nText("app.administration.rolemanagement.c4241368"), value: 3 },
    ],
    alwaysShow: true,
  },
  {
    fieldName: 'createBy',
    fieldType: 'input',
    label: i18nText("app.administration.rolemanagement.4a4d61a5"),
    placeholder: i18nText("app.administration.rolemanagement.cf038efb"),
  },
  {
    fieldName: 'createTime',
    fieldType: 'dateRangePicker',
    label: i18nText("app.administration.rolemanagement.1e1b108c"),
    placeholder: i18nText("app.administration.rolemanagement.c37f93ce"),
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

export default function RolePage() {
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

  const exportColumns = [
    {title: i18nText("app.administration.rolemanagement.a40a406e"), dataIndex: 'roleCode'},
    {title: i18nText("app.administration.rolemanagement.848cca2d"), dataIndex: 'roleName'},
    {title: i18nText("app.administration.rolemanagement.5daf81c0"), dataIndex: 'status'},
    {title: i18nText("app.administration.rolemanagement.97e4e26c"), dataIndex: 'description'},
    {title: i18nText("app.administration.rolemanagement.4a4d61a5"), dataIndex: 'createBy'},
    {title: i18nText("app.administration.rolemanagement.e072be94"), dataIndex: 'updateBy'},
    {title: i18nText("app.administration.rolemanagement.1e1b108c"), dataIndex: 'createTime'},
    {title: i18nText("app.administration.rolemanagement.c456f9fb"), dataIndex: 'updateTime'},
  ];

  const exportRows = (records: any[]) => {
    if (!records?.length) {
      message.warning(i18nText('app.administration.common.export.selectRecords'));
      return;
    }
    downloadCsv('roles.csv', records, exportColumns);
    message.success(i18nText('app.administration.common.export.success'));
  };

  const exportAll = async () => {
    try {
      const records = await fetchAllPages(listRole, tableRef.current?.getQueryParams?.() ?? {});
      exportRows(records);
    } catch (error) {
      console.error('导出全部角色失败：', error);
      message.error(i18nText('app.administration.common.export.failed'));
    }
  };

  // 操作按钮
  const actionButtons: ActionButton[] = [
    {
      text: i18nText("app.administration.rolemanagement.5091206d"),
      authority: 'role:add',
      handler: () =>
        navigate('/Administration/RoleManagement/RoleForm?mode=create'),
    },
    {
      text: i18nText("app.administration.rolemanagement.69104da2"),
      authority: 'role:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.rolemanagement.5815c49f")).then();
          return;
        }
        navigate(
          `/Administration/RoleManagement/RoleForm?mode=edit&id=${records[0].id}&roleCode=${records[0].roleCode}`,
        );
      },
    },
    {
      text: i18nText("app.administration.rolemanagement.c4241368"),
      authority: 'role:delete',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.rolemanagement.5815c49f"));
          return;
        }
        const role = records[0];
        Modal.confirm({
          title: i18nText('app.administration.rolemanagement.deactivateConfirmTitle'),
          content: i18nText('app.administration.rolemanagement.deactivateConfirmContent', {value0: role.roleName}),
          onOk: async () => {
            try {
              const result = await deactivateRole({id: role.id, roleCode: role.roleCode});
              if (!result) throw new Error('Deactivate role returned false');
              message.success(i18nText('app.administration.rolemanagement.deactivateSuccess'));
              await tableRef.current?.refresh();
            } catch (error) {
              console.error('注销角色失败：', error);
              message.error(i18nText('app.administration.rolemanagement.deactivateFailed'));
            }
          },
        });
      },
    },
    {
      text: i18nText("app.administration.rolemanagement.63cdb38c"),
      authority: 'role:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.rolemanagement.5815c49f")).then();
          return;
        }
        setSelectedRoleCode(records[0].roleCode);
        setUserModalVisible(true);
      },
    },
    {
      text: i18nText("app.administration.rolemanagement.158d4631"),
      authority: 'role:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.rolemanagement.5815c49f")).then();
          return;
        }
        setSelectedRoleCode(records[0].roleCode);
        setMenuModalVisible(true);
      },
    },
    {
      text: i18nText("app.administration.rolemanagement.f513b216"),
      authority: 'role:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.rolemanagement.5815c49f")).then();
          return;
        }
        setSelectedRoleCode(records[0].roleCode);
        setOperationModalVisible(true);
      },
    },
    {
      text: i18nText("app.administration.rolemanagement.f42921ba"),
      authority: 'role:export',
      requiresSelection: true,
      handler: (records: any) => exportRows(records),
    },
    {
      text: i18nText("app.administration.rolemanagement.9a5dd799"),
      authority: 'role:export',
      handler: () => exportAll(),
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
