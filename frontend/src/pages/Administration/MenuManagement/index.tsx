import {i18nText} from '@/utils/i18n';
import { message, Modal } from 'antd';
import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type ActionButton, SimpleTable, type TableColumn } from '@/components';
import PageWrapper from '@/components/PageWrapper';
import SearchForm from '@/components/SearchForm';
import type { SearchFieldConfig } from '@/components/SearchForm/SearchFormTypes';
import { deactivateMenu, listMenu } from '@/services/ant-design-pro/rbac';
import {downloadCsv, fetchAllPages} from '@/utils/tableExport';
import ManageOperationsModal from '../MenuOperationManagement/manageOperationsModal';

const columns: TableColumn[] = [
  {
    title: i18nText("app.administration.menumanagement.370f07aa"),
    dataIndex: 'menuCode',
    width: 100,
    sorter: true,
    order: 1,
  },
  {
    title: i18nText("app.administration.menumanagement.2dac04a1"),
    dataIndex: 'menuName',
    width: 100,
    sorter: true,
    order: 2,
  },
  {
    title: i18nText("app.administration.menumanagement.d21f17a0"),
    dataIndex: 'fatherId',
    width: 100,
    sorter: true,
    order: 3,
  },
  {
    title: i18nText("app.administration.menumanagement.c69d48ad"),
    dataIndex: 'orderId',
    width: 50,
    sorter: true,
    order: 4,
  },
  {
    title: i18nText("app.administration.menumanagement.73f96419"),
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
            {i18nText("app.administration.menumanagement.9e200b59")}
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
            {i18nText("app.administration.menumanagement.68645428")}
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
            {i18nText("app.administration.menumanagement.9269eaf4")}
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
            {i18nText("app.administration.menumanagement.bf5ee8ab")}
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
    title: i18nText("app.administration.menumanagement.b280d1f3"),
    dataIndex: 'menuUrl',
    width: 150,
    sorter: true,
    order: 6,
  },
  {
    title: i18nText("app.administration.menumanagement.b5dbc5ac"),
    dataIndex: 'description',
    width: 100,
    sorter: true,
    order: 7,
  },
  {
    title: i18nText("app.administration.menumanagement.e0e81eca"),
    dataIndex: 'createBy',
    width: 100,
    sorter: true,
    order: 8,
  },
  {
    title: i18nText("app.administration.menumanagement.9a2cd713"),
    dataIndex: 'updateBy',
    width: 100,
    sorter: true,
    order: 9,
  },
  {
    title: i18nText("app.administration.menumanagement.72cc438c"),
    dataIndex: 'createTime',
    width: 100,
    sorter: true,
    order: 10,
  },
  {
    title: i18nText("app.administration.menumanagement.31f4e630"),
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
    label: i18nText("app.administration.menumanagement.370f07aa"),
    placeholder: i18nText("app.administration.menumanagement.4120c92b"),
    alwaysShow: true,
  },
  {
    fieldName: 'menuName',
    fieldType: 'input',
    label: i18nText("app.administration.menumanagement.2dac04a1"),
    placeholder: i18nText("app.administration.menumanagement.98d02641"),
    alwaysShow: true,
  },
  {
    fieldName: 'fatherId',
    fieldType: 'input',
    label: i18nText("app.administration.menumanagement.d21f17a0"),
    placeholder: i18nText("app.administration.menumanagement.09c7f7b2"),
  },
  {
    fieldName: 'status',
    fieldType: 'select',
    label: i18nText("app.administration.menumanagement.73f96419"),
    alwaysShow: true,
    options: [
      { label: i18nText("app.administration.menumanagement.9e200b59"), value: 0 },
      { label: i18nText("app.administration.menumanagement.68645428"), value: 1 },
      { label: i18nText("app.administration.menumanagement.9269eaf4"), value: 2 },
      { label: i18nText("app.administration.menumanagement.bf5ee8ab"), value: 3 },
    ],
  },
  {
    fieldName: 'menuUrl',
    fieldType: 'input',
    label: i18nText("app.administration.menumanagement.b280d1f3"),
    placeholder: i18nText("app.administration.menumanagement.95156197"),
  },
  {
    fieldName: 'createBy',
    fieldType: 'input',
    label: i18nText("app.administration.menumanagement.e0e81eca"),
    placeholder: i18nText("app.administration.menumanagement.6508914c"),
  },
  {
    fieldName: 'createTime',
    fieldType: 'dateRangePicker',
    label: i18nText("app.administration.menumanagement.72cc438c"),
    placeholder: i18nText("app.administration.menumanagement.e69960e8"),
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

  const exportColumns = [
    {title: i18nText("app.administration.menumanagement.370f07aa"), dataIndex: 'menuCode'},
    {title: i18nText("app.administration.menumanagement.2dac04a1"), dataIndex: 'menuName'},
    {title: i18nText("app.administration.menumanagement.d21f17a0"), dataIndex: 'fatherId'},
    {title: i18nText("app.administration.menumanagement.c69d48ad"), dataIndex: 'orderId'},
    {title: i18nText("app.administration.menumanagement.73f96419"), dataIndex: 'status'},
    {title: i18nText("app.administration.menumanagement.b280d1f3"), dataIndex: 'menuUrl'},
    {title: i18nText("app.administration.menumanagement.b5dbc5ac"), dataIndex: 'description'},
    {title: i18nText("app.administration.menumanagement.e0e81eca"), dataIndex: 'createBy'},
    {title: i18nText("app.administration.menumanagement.9a2cd713"), dataIndex: 'updateBy'},
    {title: i18nText("app.administration.menumanagement.72cc438c"), dataIndex: 'createTime'},
    {title: i18nText("app.administration.menumanagement.31f4e630"), dataIndex: 'updateTime'},
  ];

  const exportRows = (records: any[]) => {
    if (!records?.length) {
      message.warning(i18nText('app.administration.common.export.selectRecords'));
      return;
    }
    downloadCsv('menus.csv', records, exportColumns);
    message.success(i18nText('app.administration.common.export.success'));
  };

  const exportAll = async () => {
    try {
      const records = await fetchAllPages(listMenu, tableRef.current?.getQueryParams?.() ?? {});
      exportRows(records);
    } catch (error) {
      console.error('导出全部菜单失败：', error);
      message.error(i18nText('app.administration.common.export.failed'));
    }
  };

  // 操作按钮
  const actionButtons: ActionButton[] = [
    {
      text: i18nText("app.administration.menumanagement.5944dfe3"),
      authority: 'menu:add',
      handler: () =>
        navigate('/Administration/MenuManagement/MenuForm?mode=create'),
    },
    {
      text: i18nText("app.administration.menumanagement.dd19304b"),
      authority: 'menu:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.menumanagement.489e0fa9")).then();
          return;
        }
        navigate(
          `/Administration/MenuManagement/MenuForm?mode=edit&id=${records[0].id}&menuCode=${records[0].menuCode}`,
        );
      },
    },
    {
      text: i18nText("app.administration.menumanagement.5c9b2d33"),
      authority: 'menu:update',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.menumanagement.489e0fa9")).then();
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
      text: i18nText("app.administration.menumanagement.b51a1a6e"),
      authority: 'menu:delete',
      requiresSelection: true,
      handler: (records: any) => {
        if (records?.length !== 1) {
          message.warning(i18nText("app.administration.menumanagement.489e0fa9"));
          return;
        }
        const menu = records[0];
        Modal.confirm({
          title: i18nText('app.administration.menumanagement.deleteConfirmTitle'),
          content: i18nText('app.administration.menumanagement.deleteConfirmContent', {value0: menu.menuName}),
          onOk: async () => {
            try {
              const result = await deactivateMenu({id: menu.id, menuCode: menu.menuCode});
              if (!result) throw new Error('Deactivate menu returned false');
              message.success(i18nText('app.administration.menumanagement.deleteSuccess'));
              await tableRef.current?.refresh();
            } catch (error) {
              console.error('删除菜单失败：', error);
              message.error(i18nText('app.administration.menumanagement.deleteFailed'));
            }
          },
        });
      },
    },
    {
      text: i18nText("app.administration.menumanagement.625c9366"),
      authority: 'menu:export',
      requiresSelection: true,
      handler: (records: any) => exportRows(records),
    },
    {
      text: i18nText("app.administration.menumanagement.54737232"),
      authority: 'menu:export',
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
