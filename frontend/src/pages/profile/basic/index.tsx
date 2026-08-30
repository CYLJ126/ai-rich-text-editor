import {i18nText} from '@/utils/i18n';
import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import type { DescriptionsProps } from 'antd';
import { Badge, Card, Descriptions, Divider, Table } from 'antd';
import type { FC } from 'react';
import React from 'react';
import type { BasicGood, BasicProgress } from './data.d';
import { queryBasicProfile } from './service';

const progressColumns: ProColumns<BasicProgress>[] = [
  {
    title: i18nText("app.examples.basic.bc92f24f"),
    dataIndex: 'time',
  },
  {
    title: i18nText("app.examples.basic.7f6bf451"),
    dataIndex: 'rate',
  },
  {
    title: i18nText("app.examples.basic.9d0ad273"),
    dataIndex: 'status',
    render: (text: React.ReactNode) => {
      if (text === 'success') {
        return <Badge status="success" text={i18nText("app.examples.basic.b29aac27")} />;
      }
      return <Badge status="processing" text={i18nText("app.examples.basic.ca708c27")} />;
    },
  },
  {
    title: i18nText("app.examples.basic.a62cf544"),
    dataIndex: 'operator',
  },
  {
    title: i18nText("app.examples.basic.b8b75dcb"),
    dataIndex: 'cost',
  },
];
const goodsColumns: ProColumns<BasicGood>[] = [
  {
    title: i18nText("app.examples.basic.c0e3e2a7"),
    dataIndex: 'id',
  },
  {
    title: i18nText("app.examples.basic.81b6c6ed"),
    dataIndex: 'name',
  },
  {
    title: i18nText("app.examples.basic.2f47a431"),
    dataIndex: 'barcode',
  },
  {
    title: i18nText("app.examples.basic.d4ba6d4e"),
    dataIndex: 'price',
  },
  {
    title: i18nText("app.examples.basic.d9bd9f84"),
    dataIndex: 'num',
    align: 'right',
  },
  {
    title: i18nText("app.examples.basic.8359758a"),
    dataIndex: 'amount',
    align: 'right',
  },
];

const Descriptions1: DescriptionsProps['items'] = [
  {
    key: '1',
    label: i18nText("app.examples.basic.660f802f"),
    children: '1000000000',
  },
  {
    key: '2',
    label: i18nText("app.examples.basic.9d0ad273"),
    children: i18nText("app.examples.basic.56f22ec9"),
  },
  {
    key: '3',
    label: i18nText("app.examples.basic.39a35677"),
    children: '1234123421',
  },
  {
    key: '4',
    label: i18nText("app.examples.basic.e7d990c8"),
    children: '3214321432',
  },
];
const Descriptions2: DescriptionsProps['items'] = [
  {
    key: '1',
    label: i18nText("app.examples.basic.1017a55c"),
    children: i18nText("app.examples.basic.a08d2308"),
  },
  {
    key: '2',
    label: i18nText("app.examples.basic.826a26c1"),
    children: '18100000000',
  },
  {
    key: '3',
    label: i18nText("app.examples.basic.b591d803"),
    children: i18nText("app.examples.basic.ceb7caab"),
  },
  {
    key: '4',
    label: i18nText("app.examples.basic.7a2904c7"),
    children: i18nText("app.examples.basic.2d9f5c56"),
  },
  {
    key: '5',
    label: i18nText("app.examples.basic.ef60f971"),
    children: i18nText("app.examples.basic.6f8d66a0"),
  },
];

const Basic: FC = () => {
  const { data, isLoading: loading } = useQuery({
    queryKey: ['profile-basic'],
    queryFn: () => queryBasicProfile().then((res) => res.data),
  });
  const { basicGoods, basicProgress } = data || {
    basicGoods: [],
    basicProgress: [],
  };
  return (
    <PageContainer>
      <Card variant="borderless">
        <Descriptions title={i18nText("app.examples.basic.bd9795a2")} items={Descriptions1} />
        <Divider size="large" />
        <Descriptions title={i18nText("app.examples.basic.981de19e")} items={Descriptions2} />
        <Divider size="large" />
        <ProTable
          headerTitle={i18nText("app.examples.basic.eb9f7e7e")}
          style={{
            marginBottom: 24,
          }}
          pagination={false}
          search={false}
          loading={loading}
          options={false}
          dataSource={basicGoods}
          ghost
          columns={goodsColumns}
          rowKey="id"
          summary={(pageData) => {
            let totalNum = 0;
            let totalAmount = 0;
            pageData.forEach(({ num, amount }) => {
              totalNum += Number(num);
              totalAmount += Number(amount);
            });
            return (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} colSpan={4}>
                  <span style={{ fontWeight: 600 }}>{i18nText("app.examples.basic.d9e80b9e")}</span>
                </Table.Summary.Cell>
                <Table.Summary.Cell index={4} align="right">
                  <span style={{ fontWeight: 600 }}>{totalNum}</span>
                </Table.Summary.Cell>
                <Table.Summary.Cell index={5} align="right">
                  <span style={{ fontWeight: 600 }}>{totalAmount}</span>
                </Table.Summary.Cell>
              </Table.Summary.Row>
            );
          }}
        />
        <ProTable
          headerTitle={i18nText("app.examples.basic.59477b4a")}
          pagination={false}
          loading={loading}
          search={false}
          options={false}
          ghost
          dataSource={basicProgress}
          columns={progressColumns}
        />
      </Card>
    </PageContainer>
  );
};
export default Basic;
