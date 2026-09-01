import {i18nText} from '@/utils/i18n';
import { Modal, message } from 'antd';
import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type ActionButton, SimpleTable, type TableColumn } from '@/components';
import PageWrapper from '@/components/PageWrapper';
import SearchForm from '@/components/SearchForm';
import type { SearchFieldConfig } from '@/components/SearchForm/SearchFormTypes';
import { deactivateUser, listUser } from '@/services/ant-design-pro/rbac';
import {downloadCsv, fetchAllPages} from '@/utils/tableExport';
import AssignMenusModal from '../MenuManagement/assignMenusModal';
import AssignOperationsModal from '../MenuOperationManagement/assignOperationsModal';
import AssignRoleModal from './assignRoleModal';

const columns: TableColumn[] = [
  {
    title: i18nText("app.administration.usermanagement.9291cf19"),
    dataIndex: 'userName',
    width: 100,
    sorter: true,
    order: 1,
  },
  {
    title: i18nText("app.administration.usermanagement.efbf1604"),
    dataIndex: 'mobile',
    width: 80,
    sorter: true,
    order: 2,
  },
  {
    title: i18nText("app.administration.usermanagement.ad5a479d"),
    dataIndex: 'email',
    width: 120,
    sorter: true,
    order: 3,
  },
  {
    title: i18nText("app.administration.usermanagement.81f6c997"),
    dataIndex: 'status',
    width: 70,
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
            {i18nText("app.administration.usermanagement.653497af")}
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
            {i18nText("app.administration.usermanagement.ffcafe9a")}
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
            {i18nText("app.administration.usermanagement.5046c5fa")}
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
            {i18nText("app.administration.usermanagement.e7f36e4d")}
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
    title: i18nText("app.administration.usermanagement.101bf14c"),
    dataIndex: 'description',
    width: 100,
    sorter: true,
    order: 9,
  },
  {
    title: i18nText("app.administration.usermanagement.8f30deb9"),
    dataIndex: 'createBy',
    width: 80,
    sorter: true,
    order: 5,
  },
  {
    title: i18nText("app.administration.usermanagement.930de5f2"),
    dataIndex: 'updateBy',
    width: 80,
    sorter: true,
    order: 6,
  },
  {
    title: i18nText("app.administration.usermanagement.7cb324b0"),
    dataIndex: 'createTime',
    width: 100,
    sorter: true,
    order: 7,
  },
  {
    title: i18nText("app.administration.usermanagement.acf4d00a"),
    dataIndex: 'updateTime',
    width: 100,
    sorter: true,
    order: 8,
  },
];

const searchFields: SearchFieldConfig[] = [
  {
    fieldName: 'userName',
    fieldType: 'input',
    label: i18nText("app.administration.usermanagement.9291cf19"),
    placeholder: i18nText("app.administration.usermanagement.0213d713"),
    alwaysShow: true,
  },
  {
    fieldName: 'status',
    fieldType: 'select',
    label: i18nText("app.administration.usermanagement.81f6c997"),
    options: [
      { label: i18nText("app.administration.usermanagement.653497af"), value: 0 },
      { label: i18nText("app.administration.usermanagement.ffcafe9a"), value: 1 },
      { label: i18nText("app.administration.usermanagement.5046c5fa"), value: 2 },
      { label: i18nText("app.administration.usermanagement.e7f36e4d"), value: 3 },
    ],
  },
  {
    fieldName: 'mobile',
    fieldType: 'input',
    label: i18nText("app.administration.usermanagement.efbf1604"),
    placeholder: i18nText("app.administration.usermanagement.fb4217ba"),
    alwaysShow: true,
  },
  {
    fieldName: 'email',
    fieldType: 'input',
    label: i18nText("app.administration.usermanagement.ad5a479d"),
    placeholder: i18nText("app.administration.usermanagement.dfec38d6"),
    alwaysShow: true,
  },
  {
    fieldName: 'createBy',
    fieldType: 'input',
    label: i18nText("app.administration.usermanagement.8f30deb9"),
    placeholder: i18nText("app.administration.usermanagement.18a9c348"),
    alwaysShow: true,
  },
  {
    fieldName: 'createTime',
    fieldType: 'dateRangePicker',
    label: i18nText("app.administration.usermanagement.7cb324b0"),
    placeholder: i18nText("app.administration.usermanagement.3ff3c6ae"),
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

  const exportColumns = [
    {title: i18nText("app.administration.usermanagement.9291cf19"), dataIndex: 'userName'},
    {title: i18nText("app.administration.usermanagement.efbf1604"), dataIndex: 'mobile'},
    {title: i18nText("app.administration.usermanagement.ad5a479d"), dataIndex: 'email'},
    {title: i18nText("app.administration.usermanagement.81f6c997"), dataIndex: 'status'},
    {title: i18nText("app.administration.usermanagement.101bf14c"), dataIndex: 'description'},
    {title: i18nText("app.administration.usermanagement.8f30deb9"), dataIndex: 'createBy'},
    {title: i18nText("app.administration.usermanagement.930de5f2"), dataIndex: 'updateBy'},
    {title: i18nText("app.administration.usermanagement.7cb324b0"), dataIndex: 'createTime'},
    {title: i18nText("app.administration.usermanagement.acf4d00a"), dataIndex: 'updateTime'},
  ];

  const exportRows = (records: any[]) => {
    if (!records?.length) {
      message.warning(i18nText('app.administration.common.export.selectRecords')).then();
      return;
    }
    downloadCsv('users.csv', records, exportColumns);
    message.success(i18nText('app.administration.common.export.success')).then();
  };

  const exportAll = async () => {
    try {
      const records = await fetchAllPages(listUser, tableRef.current?.getQueryParams?.() ?? {});
      exportRows(records);
    } catch (error) {
      console.error('导出全部用户失败：', error);
      message.error(i18nText('app.administration.common.export.failed')).then();
    }
  };

  // 操作按钮
  const actionButtons: ActionButton[] = [
    {
      text: i18nText("app.administration.usermanagement.27d8ddf2"),
      authority: 'user:add',
      handler: () =>
        navigate('/Administration/UserManagement/UserForm?mode=create'),
    },
    {
      text: i18nText("app.administration.usermanagement.c8855b36"),
      authority: 'user:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.usermanagement.3ebdcf2b")).then();
          return;
        }
        navigate(
          `/Administration/UserManagement/UserForm?mode=edit&id=${records[0].id}&userName=${records[0].userName}`,
        );
      },
    },
    {
      text: i18nText("app.administration.usermanagement.e7f36e4d"),
      authority: 'user:delete',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.usermanagement.3ebdcf2b")).then();
          return;
        }
        const userName = records[0].userName;
        Modal.confirm({
          title: i18nText("app.administration.usermanagement.4a689783"),
          content: i18nText("app.administration.usermanagement.10a7a066", {value0: userName}),
          okText: i18nText("app.administration.usermanagement.3a69d6a4"),
          cancelText: i18nText("app.administration.usermanagement.25ab89de"),
          onOk: async () => {
            try {
              const result = await deactivateUser(userName);
              if (!result) throw new Error('Deactivate user returned false');
              message.success(i18nText("app.administration.usermanagement.f1168551")).then();
              await tableRef.current?.refresh();
            } catch (error) {
              message.error(i18nText("app.administration.usermanagement.4dddeb37")).then();
              console.error('注销用户失败：', error);
            }
          },
        });
      },
    },
    {
      text: i18nText("app.administration.usermanagement.89cb5e5c"),
      authority: 'user:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.usermanagement.3ebdcf2b")).then();
          return;
        }
        setSelectedUserName(records[0].userName);
        setRoleModalVisible(true);
      },
    },
    {
      text: i18nText("app.administration.usermanagement.c3cbc231"),
      authority: 'user:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.usermanagement.3ebdcf2b")).then();
          return;
        }
        setSelectedUserName(records[0].userName);
        setMenuModalVisible(true);
      },
    },
    {
      text: i18nText("app.administration.usermanagement.e09d88c1"),
      authority: 'user:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.usermanagement.3ebdcf2b")).then();
          return;
        }
        setSelectedUserName(records[0].userName);
        setOperationModalVisible(true);
      },
    },
    {
      text: i18nText("app.administration.usermanagement.31d8f455"),
      authority: 'user:export',
      requiresSelection: true,
      handler: (records: any) => exportRows(records),
    },
    {
      text: i18nText("app.administration.usermanagement.ff54ba35"),
      authority: 'user:export',
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
          style={{ marginBottom: 10 }}
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
