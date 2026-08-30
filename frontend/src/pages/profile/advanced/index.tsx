import {i18nText} from '@/utils/i18n';
import {DingdingOutlined, DownOutlined, EllipsisOutlined, InfoCircleOutlined,} from '@ant-design/icons';
import {GridContent, PageContainer, RouteContext,} from '@ant-design/pro-components';
import {useQuery} from '@tanstack/react-query';
import type {DescriptionsProps} from 'antd';
import {
  Badge,
  Button,
  Card,
  Descriptions,
  Divider,
  Dropdown,
  Empty,
  Popover,
  Space,
  Statistic,
  Steps,
  Table,
  Tooltip,
} from 'antd';
import type {IconRenderType, StepsProps} from 'antd/es/steps';
import type {FC} from 'react';
import React, {useState} from 'react';
import type {AdvancedProfileData} from './data.d';
import {queryAdvancedProfile} from './service';
import useStyles from './style.style';

const action = (
  <RouteContext.Consumer>
    {({ isMobile }) => {
      if (isMobile) {
        return (
          <Dropdown.Button
            type="primary"
            icon={<DownOutlined />}
            menu={{
              items: [
                {
                  key: '1',
                  label: i18nText("app.examples.advanced.5216ef11"),
                },
                {
                  key: '2',
                  label: i18nText("app.examples.advanced.0cb73d75"),
                },
                {
                  key: '3',
                  label: i18nText("app.examples.advanced.c0c263e1"),
                },
              ],
            }}
            placement="bottomRight"
          >
            {i18nText("app.examples.advanced.64d74d55")}
          </Dropdown.Button>
        );
      }
      return (
        <Space>
          <Space.Compact>
            <Button>{i18nText("app.examples.advanced.5216ef11")}</Button>
            <Button>{i18nText("app.examples.advanced.0cb73d75")}</Button>
            <Dropdown
              menu={{
                items: [
                  {
                    key: '1',
                    label: i18nText("app.examples.advanced.5633edea"),
                  },
                  {
                    key: '2',
                    label: i18nText("app.examples.advanced.909d7941"),
                  },
                  {
                    key: '3',
                    label: i18nText("app.examples.advanced.0fccb00b"),
                  },
                ],
              }}
              placement="bottomRight"
            >
              <Button>
                <EllipsisOutlined />
              </Button>
            </Dropdown>
          </Space.Compact>
          <Button type="primary">{i18nText("app.examples.advanced.64d74d55")}</Button>
        </Space>
      );
    }}
  </RouteContext.Consumer>
);

const operationTabList = [
  {
    key: 'tab1',
    tab: i18nText("app.examples.advanced.cf8abc80"),
  },
  {
    key: 'tab2',
    tab: i18nText("app.examples.advanced.d9905835"),
  },
  {
    key: 'tab3',
    tab: i18nText("app.examples.advanced.386ebda7"),
  },
];
const columns = [
  {
    title: i18nText("app.examples.advanced.5d4d6ccb"),
    dataIndex: 'type',
    key: 'type',
  },
  {
    title: i18nText("app.examples.advanced.544d902d"),
    dataIndex: 'name',
    key: 'name',
  },
  {
    title: i18nText("app.examples.advanced.cd71e986"),
    dataIndex: 'status',
    key: 'status',
    render: (text: string) => {
      if (text === 'agree') {
        return <Badge status="success" text={i18nText("app.examples.advanced.3a50b234")} />;
      }
      return <Badge status="error" text={i18nText("app.examples.advanced.05b70e71")} />;
    },
  },
  {
    title: i18nText("app.examples.advanced.f652bf9f"),
    dataIndex: 'updatedAt',
    key: 'updatedAt',
  },
  {
    title: i18nText("app.examples.advanced.ab4d12be"),
    dataIndex: 'memo',
    key: 'memo',
  },
];
const descriptionItems: DescriptionsProps['items'] = [
  { key: '1', label: i18nText("app.examples.advanced.84204ec6"), children: i18nText("app.examples.advanced.8d661755") },
  { key: '2', label: i18nText("app.examples.advanced.b1948d57"), children: i18nText("app.examples.advanced.7aa4d518") },
  { key: '3', label: i18nText("app.examples.advanced.ac055aa3"), children: '2017-07-07' },
  {key: '4', label: i18nText("app.examples.advanced.76260b30"), children: <a href="/">12421</a>},
  { key: '5', label: i18nText("app.examples.advanced.1feeac01"), children: '2017-07-07 ~ 2017-08-08' },
  { key: '6', label: i18nText("app.examples.advanced.ab4d12be"), children: i18nText("app.examples.advanced.8e2c5edc") },
];
const userInfoItems: DescriptionsProps['items'] = [
  { key: '1', label: i18nText("app.examples.advanced.9e18575a"), children: i18nText("app.examples.advanced.7db78c09") },
  { key: '2', label: i18nText("app.examples.advanced.16fa7297"), children: '32943898021309809423' },
  { key: '3', label: i18nText("app.examples.advanced.2a59d0e4"), children: '3321944288191034921' },
  { key: '4', label: i18nText("app.examples.advanced.08d5871a"), children: '18112345678' },
  {
    key: '5',
    label: i18nText("app.examples.advanced.3e3d5cb2"),
    children: i18nText("app.examples.advanced.823bd697"),
  },
];
const infoGroupItems: DescriptionsProps['items'] = [
  { key: '1', label: i18nText("app.examples.advanced.8e808f84"), children: '725' },
  { key: '2', label: i18nText("app.examples.advanced.a67bd555"), children: '2017-08-08' },
  {
    key: '3',
    label: (
      <span>
        {i18nText("app.examples.advanced.8e808f84")}
        <Tooltip title={i18nText("app.examples.advanced.386fcf9b")}>
          <InfoCircleOutlined
            style={{ color: 'rgba(0, 0, 0, 0.43)', marginLeft: 4 }}
          />
        </Tooltip>
      </span>
    ),
    children: '725',
  },
  { key: '4', label: i18nText("app.examples.advanced.a67bd555"), children: '2017-08-08' },
];
const groupItems1: DescriptionsProps['items'] = [
  { key: '1', label: i18nText("app.examples.advanced.26266779"), children: i18nText("app.examples.advanced.e54b26aa") },
  { key: '2', label: i18nText("app.examples.advanced.fed2d63b"), children: '1234567' },
  { key: '3', label: i18nText("app.examples.advanced.6e1f474b"), children: i18nText("app.examples.advanced.09ea49d3") },
  { key: '4', label: i18nText("app.examples.advanced.13589dee"), children: '2017-08-08' },
  {
    key: '5',
    label: i18nText("app.examples.advanced.920b4951"),
    children:
      i18nText("app.examples.advanced.6b70a1b7"),
  },
];
const groupItems2: DescriptionsProps['items'] = [
  {
    key: '1',
    label: i18nText("app.examples.advanced.21518ac5"),
    children:
      i18nText("app.examples.advanced.92f445fd"),
  },
];
const groupItems3: DescriptionsProps['items'] = [
  { key: '1', label: i18nText("app.examples.advanced.26266779"), children: i18nText("app.examples.advanced.7db78c09") },
  { key: '2', label: i18nText("app.examples.advanced.fed2d63b"), children: '1234568' },
];
const customDot: IconRenderType = (dot: React.ReactNode, { active }) => {
  if (active) {
    const popoverContent = (
      <div
        style={{
          width: 160,
        }}
      >
        {i18nText("app.examples.advanced.3299c0f6")}
        <span
          style={{
            float: 'right',
          }}
        >
          <Badge
            status="default"
            text={
              <span
                style={{
                  color: 'rgba(0, 0, 0, 0.45)',
                }}
              >
                {i18nText("app.examples.advanced.ec20ffad")}
              </span>
            }
          />
        </span>
        <div
          style={{
            marginTop: 4,
          }}
        >
          {i18nText("app.examples.advanced.787d18b4")}
        </div>
      </div>
    );
    return (
      <Popover
        placement="topLeft"
        arrow={{
          pointAtCenter: true,
        }}
        content={popoverContent}
      >
        <span>{dot}</span>
      </Popover>
    );
  }
  return dot;
};

type AdvancedState = {
  operationKey: 'tab1' | 'tab2' | 'tab3';
  tabActiveKey: string;
};
const Advanced: FC = () => {
  const { styles } = useStyles();

  const extra = (
    <div className={styles.moreInfo}>
      <Statistic title={i18nText("app.examples.advanced.721b5164")} value={i18nText("app.examples.advanced.03574d00")} />
      <Statistic title={i18nText("app.examples.advanced.0025cc72")} value={568.08} prefix="¥" />
    </div>
  );
  const description = (
    <RouteContext.Consumer>
      {({ isMobile }) => (
        <Descriptions
          className={styles.headerList}
          size="small"
          column={isMobile ? 1 : 2}
          items={descriptionItems}
        />
      )}
    </RouteContext.Consumer>
  );
  const desc1 = (
    <div className={styles.stepDescription}>
      {i18nText("app.examples.advanced.8d661755")}
      <DingdingOutlined
        style={{
          marginLeft: 8,
        }}
      />
      <div>2016-12-12 12:32</div>
    </div>
  );
  const desc2 = (
    <div className={styles.stepDescription}>
      {i18nText("app.examples.advanced.3d35d601")}
      <DingdingOutlined
        style={{
          color: '#00A0E9',
          marginLeft: 8,
        }}
      />
      <div>
        <Button type="link" style={{padding: 0}}>
          {i18nText("app.examples.advanced.78ca3836")}
        </Button>
      </div>
    </div>
  );
  const stepsItems: StepsProps['items'] = [
    { title: i18nText("app.examples.advanced.baa76298"), content: desc1 },
    { title: i18nText("app.examples.advanced.05488d64"), content: desc2 },
    { title: i18nText("app.examples.advanced.2ccd5e71") },
    { title: i18nText("app.examples.advanced.a33fc6a5") },
  ];

  const [tabStatus, seTabStatus] = useState<AdvancedState>({
    operationKey: 'tab1',
    tabActiveKey: 'detail',
  });

  const { data = {}, isLoading: loading } = useQuery<AdvancedProfileData>({
    queryKey: ['profile-advanced'],
    queryFn: () => queryAdvancedProfile().then((res) => res.data),
  });
  const { advancedOperation1, advancedOperation2, advancedOperation3 } = data;
  const contentList = {
    tab1: (
      <Table
        pagination={false}
        loading={loading}
        dataSource={advancedOperation1}
        columns={columns}
      />
    ),
    tab2: (
      <Table
        pagination={false}
        loading={loading}
        dataSource={advancedOperation2}
        columns={columns}
      />
    ),
    tab3: (
      <Table
        pagination={false}
        loading={loading}
        dataSource={advancedOperation3}
        columns={columns}
      />
    ),
  };
  const onTabChange = (tabActiveKey: string) => {
    seTabStatus({
      ...tabStatus,
      tabActiveKey,
    });
  };
  const onOperationTabChange = (key: string) => {
    seTabStatus({
      ...tabStatus,
      operationKey: key as 'tab1',
    });
  };
  return (
    <PageContainer
      title={i18nText("app.examples.advanced.6a6baa76")}
      extra={action}
      className={styles.pageHeader}
      content={description}
      extraContent={extra}
      tabActiveKey={tabStatus.tabActiveKey}
      onTabChange={onTabChange}
      tabList={[
        {
          key: 'detail',
          tab: i18nText("app.examples.advanced.d78746c6"),
        },
        {
          key: 'rule',
          tab: i18nText("app.examples.advanced.f3915925"),
        },
      ]}
    >
      <div className={styles.main}>
        <GridContent>
          <Card
            title={i18nText("app.examples.advanced.ec5112cf")}
            style={{
              marginBottom: 24,
            }}
          >
            <RouteContext.Consumer>
              {({ isMobile }) => (
                <Steps
                  orientation={isMobile ? 'vertical' : 'horizontal'}
                  iconRender={customDot}
                  current={1}
                  items={stepsItems}
                />
              )}
            </RouteContext.Consumer>
          </Card>
          <Card
            title={i18nText("app.examples.advanced.248621c7")}
            style={{
              marginBottom: 24,
            }}
            variant="borderless"
          >
            <Descriptions
              style={{
                marginBottom: 24,
              }}
              items={userInfoItems}
            />
            <Descriptions
              style={{
                marginBottom: 24,
              }}
              title={i18nText("app.examples.advanced.346abfca")}
              items={infoGroupItems}
            />
            <h4
              style={{
                marginBottom: 16,
              }}
            >
              {i18nText("app.examples.advanced.346abfca")}
            </h4>
            <Card type="inner" title={i18nText("app.examples.advanced.8be096e3")}>
              <Descriptions title={i18nText("app.examples.advanced.95221b5c")} items={groupItems1} />
              <Divider size="large" />
              <Descriptions title={i18nText("app.examples.advanced.95221b5c")} column={1} items={groupItems2} />
              <Divider size="large" />
              <Descriptions title={i18nText("app.examples.advanced.95221b5c")} items={groupItems3} />
            </Card>
          </Card>
          <Card
            title={i18nText("app.examples.advanced.76285ada")}
            style={{
              marginBottom: 24,
            }}
            variant="borderless"
          >
            <Empty />
          </Card>
          <Card
            variant="borderless"
            tabList={operationTabList}
            onTabChange={onOperationTabChange}
          >
            {contentList[tabStatus.operationKey] as React.ReactNode}
          </Card>
        </GridContent>
      </div>
    </PageContainer>
  );
};
export default Advanced;
