import React, {forwardRef, useCallback, useEffect, useImperativeHandle, useState} from 'react';
import {Button, message, Space, Table} from 'antd';
import type {ColumnType, TableProps} from 'antd/es/table';
import type {SorterResult} from 'antd/es/table/interface';
import {generateRandomUUID} from '@/utils/RandomUtil';
import {getColorByIndex} from '@/utils/colorUtil';
import styles from './index.less';
import {useModel} from '@@/exports';

interface Statistics {
  order?: number;
  label: string;
  value: string | number;
}

interface Pagination {
  current: number;
  size: number;
  total: number;
}

interface QueryParam {
  [key: string]: any;
}

interface ActionButton {
  text: string;
  authority: string;
  handler: (selectedRows?: any[]) => void;
  requiresSelection?: boolean;
}

interface TableColumn extends ColumnType<any> {
  dataIndex?: string;
  title: string;
  width?: number;
  sorter?: boolean;
  fixed?: 'left' | 'right';
  order?: number; // 列显示顺序
}

interface ApiResponse {
  code: string;
  success: boolean;
  current: number;
  totalPage: number;
  data: any[];
  statistics: Statistics;
}

interface AdvancedTableProps {
  columns: TableColumn[];
  fetchData: (
      params: QueryParam & {
        current: number;
        size: number;
        sortField?: string;
        sortOrder?: 'ascend' | 'descend';
      },
  ) => Promise<ApiResponse>;
  initialParams?: QueryParam;
  actionButtons?: ActionButton[];
  rowKey?: string;
  defaultSize?: number;
  defaultSelectedField?: string; // 用于设置默认选中行的字段名，值为true/false
}

// 格式化统计信息
const formatStatistics = (stats: Statistics): string => {
  if (!stats) {
    return '';
  }
  // 统计信息格式：[{order: 1, label: '总笔数', value: 85}, {order: 2, label: '总金额', value: '¥12,345.67'}]
  return Object.entries(stats)
      .sort((a, b) => a[1].order - b[1].order)
      .map(([label, value]) => `${label}: ${value}`)
      .join(' | ');
};

const SimpleTable = forwardRef((props, ref) => {
  const {
    columns,
    fetchData,
    initQueryParam = {},
    actionButtons = [],
    rowKey = 'id',
    defaultSize = 50,
    showStatistics = true,
    tableHeight = 750,
    defaultSelectedField,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    doubleClick = (_) => {
    },
  } = props;
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState<Pagination>({
    current: 1,
    size: defaultSize,
    total: 0,
  });
  const [statistics, setStatistics] = useState<string>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [selectedRows, setSelectedRows] = useState<any[]>([]);
  const [sorter, setSorter] = useState<{
    field?: string;
    order?: 'ascend' | 'descend'
  }>({});
  const [queryParam, setQueryParam] = useState<QueryParam>({...initQueryParam});
  const {initialState} = useModel('@@initialState');
  const buttons = actionButtons.filter((button: ActionButton) =>
      initialState?.currentUser?.menuOperations?.includes(button.authority),
  );

  // 处理列排序和添加固定列
  const getProcessedColumns = useCallback((): TableColumn[] => {
    // 按order排序
    const sortedColumns = [...columns].sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    return [
      ...sortedColumns.map((col) => ({
        ...col,
        sorter: col.sorter ? true : undefined,
      })),
    ];
  }, [columns]);

  // 加载数据
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const params = {
        ...queryParam,
        current: pagination.current,
        size: pagination.size,
        sortField: sorter.field,
        sortOrder: sorter.order,
      };
      const response = await fetchData(params);

      if (response.success) {
        const newData = response.records;
        setData(newData);
        setStatistics(formatStatistics(response.statistics));
        setPagination((prev) => ({
          ...prev,
          total: response.total,
        }));

        // 根据defaultSelectedField设置默认选中状态
        if (defaultSelectedField) {
          const defaultSelectedKeys = newData
              .filter((record: any) => record[defaultSelectedField])
              .map((record: any) => record[rowKey]);
          const defaultSelectedRows = newData.filter((record: any) => record[defaultSelectedField]);
          setSelectedRowKeys(defaultSelectedKeys);
          setSelectedRows(defaultSelectedRows);
        } else {
          // 重置选中状态
          setSelectedRowKeys([]);
          setSelectedRows([]);
        }
      } else {
        message.error('加载数据失败');
      }
    } catch (error) {
      message.error('加载数据失败');
      console.error('加载数据失败:', error);
    } finally {
      setLoading(false);
    }
  }, [
    fetchData,
    queryParam,
    pagination.current,
    pagination.size,
    sorter.field,
    sorter.order,
    defaultSelectedField,
    rowKey,
  ]);

  // 处理分页变化
  const handlePaginationChange = (page: number, size?: number) => {
    setPagination({
      current: page,
      size: size ?? pagination.size,
      total: pagination.total,
    });
  };

  // 处理排序变化
  const handleTableChange: TableProps<any>['onChange'] = (
      _,
      filters,
      sorterResult: SorterResult<any> | SorterResult<any>[],
  ) => {
    const sorterArray = Array.isArray(sorterResult) ? sorterResult : [sorterResult];
    const validSorter = sorterArray.find((s) => s.column?.sorter && s.order);

    if (validSorter) {
      setSorter({
        field: validSorter.field as string,
        order: validSorter.order === 'ascend' ? 'ascend' : 'descend',
      });
    } else {
      setSorter({});
    }
  };

  // 当查询参数变化时重置到第一页
  useEffect(() => {
    setPagination((prev) => ({...prev, current: 1}));
  }, [queryParam]);

  // 监听参数变化自动刷新
  useEffect(() => {
    loadData().then();
  }, [pagination.current, pagination.size, sorter, queryParam, loadData]);

  // 暴露刷新方法给父组件
  useImperativeHandle(ref, () => ({
    refresh: () => loadData(),
    query: (params: QueryParam) => setQueryParam(params),
    getSelectedRows: () => selectedRows,
  }));
  return (
      <div className={styles.tableContainer}>
        {/* 按钮行 */}
        {buttons.length > 0 && (
            <div className={styles.buttonBar}>
              <Space>
                {buttons.map((btn: any, index: any) => (
                    <Button
                        key={generateRandomUUID(8)}
                        type="primary"
                        disabled={btn.requiresSelection && selectedRows.length === 0}
                        onClick={() => btn.handler(selectedRows)}
                        style={{backgroundColor: getColorByIndex(index)}}
                    >
                      {btn.text}
                    </Button>
                ))}
              </Space>
            </div>
        )}

        {/* 统计行 */}
        {showStatistics && statistics && (
            <div className={styles.summary}>
              <span>{statistics}</span>
            </div>
        )}

        <hr className={styles.headLine}/>

        {/* 表格 */}
        <Table
            className={styles.table}
            columns={getProcessedColumns()}
            dataSource={data}
            rowKey={rowKey}
            loading={loading}
            pagination={{
              current: pagination.current,
              pageSize: pagination.size,
              total: pagination.total,
              showSizeChanger: true,
              showQuickJumper: true,
              onChange: handlePaginationChange,
              onShowSizeChange: (current, size) => handlePaginationChange(1, size),
              showTotal: (total) => `共 ${total} 条记录`,
              pageSizeOptions: [20, 50, 100, 200, 500],
            }}
            onChange={handleTableChange}
            scroll={{x: 1500, y: tableHeight - 55}}
            sticky
            rowSelection={{
              fixed: 'left',
              columnWidth: 50,
              selectedRowKeys,
              onChange: (selectedKeys, selectedRows) => {
                setSelectedRowKeys(selectedKeys);
                setSelectedRows(selectedRows);
              },
              getCheckboxProps: (record) => ({
                disabled: record.disabled,
              }),
            }}
            onRow={(record) => ({
              onDoubleClick: () => {
                doubleClick(record);
              },
            })}
        />
      </div>
  );
});

export default SimpleTable;
export type {ActionButton, TableColumn, Statistics, QueryParam, ApiResponse, AdvancedTableProps};
