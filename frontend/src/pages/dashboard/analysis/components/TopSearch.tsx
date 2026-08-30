import {i18nText} from '@/utils/i18n';
import {InfoCircleOutlined} from '@ant-design/icons';
import {Area} from '@ant-design/plots';
import {Card, Col, Row, Table, Tooltip} from 'antd';
import React from 'react';
import {formatNumber} from '@/utils/format';
import type {DataItem} from '../data.d';
import NumberInfo from './NumberInfo';
import Trend from './Trend';

const renderSearchUserSubtitle = () => (
  <span>
    {i18nText("app.dashboard.components.topsearch.146bef7c")}
    <Tooltip title={i18nText("app.dashboard.components.topsearch.fc8548e8")}>
      <InfoCircleOutlined
        style={{
          marginLeft: 8,
        }}
      />
    </Tooltip>
  </span>
);

const renderAverageSearchSubtitle = () => (
  <span>
    {i18nText("app.dashboard.components.topsearch.db2872e3")}
    <Tooltip title={i18nText("app.dashboard.components.topsearch.fc8548e8")}>
      <InfoCircleOutlined
        style={{
          marginLeft: 8,
        }}
      />
    </Tooltip>
  </span>
);

const TopSearch = ({
  loading,
  visitData2,
  searchData,
                     renderDropdownGroup,
}: {
  loading: boolean;
  visitData2: DataItem[];
  renderDropdownGroup: () => React.ReactNode;
  searchData: DataItem[];
}) => {
  const dropdownGroup = renderDropdownGroup();
  const columns = [
    {
      title: i18nText("app.dashboard.components.topsearch.452a6961"),
      dataIndex: 'index',
      key: 'index',
    },
    {
      title: i18nText("app.dashboard.components.topsearch.b061cd2c"),
      dataIndex: 'keyword',
      key: 'keyword',
      render: (text: React.ReactNode) => <a href="/">{text}</a>,
    },
    {
      title: i18nText("app.dashboard.components.topsearch.da4c0cf9"),
      dataIndex: 'count',
      key: 'count',
      sorter: (
        a: {
          count: number;
        },
        b: {
          count: number;
        },
      ) => a.count - b.count,
    },
    {
      title: i18nText("app.dashboard.components.topsearch.0ca9a4cf"),
      dataIndex: 'range',
      key: 'range',
      sorter: (
        a: {
          range: number;
        },
        b: {
          range: number;
        },
      ) => a.range - b.range,
      render: (
        text: React.ReactNode,
        record: {
          status: number;
        },
      ) => (
        <Trend flag={record.status === 1 ? 'down' : 'up'}>
          <span
            style={{
              marginRight: 4,
            }}
          >
            {text}%
          </span>
        </Trend>
      ),
    },
  ];
  return (
    <Card
      loading={loading}
      variant="borderless"
      title={i18nText("app.dashboard.components.topsearch.ac33ccca")}
      extra={dropdownGroup}
      style={{
        height: '100%',
      }}
    >
      <Row gutter={68}>
        <Col
          sm={12}
          xs={24}
          style={{
            marginBottom: 24,
          }}
        >
          <NumberInfo
            renderSubTitle={renderSearchUserSubtitle}
            gap={8}
            total={formatNumber(12321)}
            status="up"
            subTotal={17.1}
          />
          <Area
            xField="x"
            yField="y"
            shapeField="smooth"
            height={45}
            axis={false}
            padding={-12}
            style={{
              fill: 'linear-gradient(-90deg, white 0%, #6294FA 100%)',
              fillOpacity: 0.4,
            }}
            data={visitData2}
          />
        </Col>
        <Col
          sm={12}
          xs={24}
          style={{
            marginBottom: 24,
          }}
        >
          <NumberInfo
            renderSubTitle={renderAverageSearchSubtitle}
            total={2.7}
            status="down"
            subTotal={26.2}
            gap={8}
          />
          <Area
            xField="x"
            yField="y"
            shapeField="smooth"
            height={45}
            padding={-12}
            style={{
              fill: 'linear-gradient(-90deg, white 0%, #6294FA 100%)',
              fillOpacity: 0.4,
            }}
            data={visitData2}
            axis={false}
          />
        </Col>
      </Row>
      <Table<any>
        rowKey={(record) => record.index}
        size="small"
        columns={columns}
        dataSource={searchData}
        pagination={{
          style: {
            marginBottom: 0,
          },
          pageSize: 5,
        }}
      />
    </Card>
  );
};
export default TopSearch;
