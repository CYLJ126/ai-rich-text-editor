import {i18nText} from '@/utils/i18n';
import {Radar} from '@ant-design/plots';
import {PageContainer} from '@ant-design/pro-components';
import {useQuery} from '@tanstack/react-query';
import {Link} from '@umijs/max';
import {Avatar, Card, Col, List, Row, Skeleton, Statistic} from 'antd';
import dayjs from 'dayjs';
import type {FC} from 'react';
import EditableLinkGroup from './components/EditableLinkGroup';
import type {ActivitiesType, CurrentUser} from './data.d';
import {fakeChartData, queryActivities, queryProjectNotice} from './service';
import useStyles from './style.style';

const links = [
  {
    title: i18nText("app.dashboard.workplace.496745c4"),
    href: '',
  },
  {
    title: i18nText("app.dashboard.workplace.c21a0f88"),
    href: '',
  },
  {
    title: i18nText("app.dashboard.workplace.bacd55b3"),
    href: '',
  },
  {
    title: i18nText("app.dashboard.workplace.38018fec"),
    href: '',
  },
  {
    title: i18nText("app.dashboard.workplace.695c582f"),
    href: '',
  },
  {
    title: i18nText("app.dashboard.workplace.36735aec"),
    href: '',
  },
];
const PageHeaderContent: FC<{
  currentUser: Partial<CurrentUser>;
}> = ({ currentUser }) => {
  const { styles } = useStyles();
  const loading = currentUser && Object.keys(currentUser).length;
  if (!loading) {
    return (
      <Skeleton
        avatar
        paragraph={{
          rows: 1,
        }}
        active
      />
    );
  }
  return (
    <div className={styles.pageHeaderContent}>
      <div className={styles.avatar}>
        <Avatar size="large" src={currentUser.avatar} />
      </div>
      <div className={styles.content}>
        <div className={styles.contentTitle}>
          {i18nText("app.dashboard.workplace.9239f213")}
          {currentUser.name}
          {i18nText("app.dashboard.workplace.c9cb7439")}
        </div>
        <div>
          {currentUser.title} | {currentUser.group}
        </div>
      </div>
    </div>
  );
};
const ExtraContent: FC<Record<string, any>> = () => {
  const { styles } = useStyles();
  return (
    <div className={styles.extraContent}>
      <div className={styles.statItem}>
        <Statistic title={i18nText("app.dashboard.workplace.ecc1a898")} value={56} />
      </div>
      <div className={styles.statItem}>
        <Statistic title={i18nText("app.dashboard.workplace.fe00260d")} value={8} suffix="/ 24" />
      </div>
      <div className={styles.statItem}>
        <Statistic title={i18nText("app.dashboard.workplace.d99f9413")} value={2223} />
      </div>
    </div>
  );
};
const Workplace: FC = () => {
  const { styles } = useStyles();
  const { isLoading: projectLoading, data: projectNotice = [] } = useQuery({
    queryKey: ['project-notice'],
    queryFn: () => queryProjectNotice().then((res) => res.data),
  });
  const { isLoading: activitiesLoading, data: activities = [] } = useQuery({
    queryKey: ['activities'],
    queryFn: () => queryActivities().then((res) => res.data),
  });
  const { data } = useQuery({
    queryKey: ['workplace-chart'],
    queryFn: () => fakeChartData().then((res) => res.data),
  });
  const renderActivities = (item: ActivitiesType) => {
    const events = item.template.split(/@\{([^{}]*)\}/gi).map((key) => {
      if (item[key as keyof ActivitiesType]) {
        const value = item[key as 'user'];
        return (
          <a href={value?.link} key={value?.name}>
            {value.name}
          </a>
        );
      }
      return key;
    });
    return (
      <List.Item key={item.id}>
        <List.Item.Meta
          avatar={<Avatar src={item.user.avatar} />}
          title={
            <span>
              <a className={styles.username} href={item.user.link || '/'}>
                {item.user.userName}
              </a>
              &nbsp;
              <span className={styles.event}>{events}</span>
            </span>
          }
          description={
            <span className={styles.datetime} title={item.updatedAt}>
              {dayjs(item.updatedAt).fromNow()}
            </span>
          }
        />
      </List.Item>
    );
  };

  return (
    <PageContainer
      content={
        <PageHeaderContent
          currentUser={{
            avatar:
              'https://gw.alipayobjects.com/zos/rmsportal/BiazfanxmamNRoxxVxka.png',
            name: i18nText("app.dashboard.workplace.208da994"),
            userid: '00000001',
            email: 'antdesign@alipay.com',
            signature: i18nText("app.dashboard.workplace.382fab58"),
            title: i18nText("app.dashboard.workplace.f56fa4a3"),
            group: i18nText("app.dashboard.workplace.d6af3066"),
          }}
        />
      }
      extraContent={<ExtraContent />}
    >
      <Row gutter={24}>
        <Col xl={16} lg={24} md={24} sm={24} xs={24}>
          <Card
            className={styles.projectList}
            style={{
              marginBottom: 24,
            }}
            title={i18nText("app.dashboard.workplace.d120e1fd")}
            variant="borderless"
            extra={
              <Link to="/" prefetch>
                {i18nText("app.dashboard.workplace.8a8ad9ce")}
              </Link>
            }
            loading={projectLoading}
          >
            {projectNotice.map((item) => (
              <Card.Grid className={styles.projectGrid} key={item.id}>
                <Card.Meta
                  title={
                    <div className={styles.cardTitle}>
                      <Avatar size="small" src={item.logo} />
                      <Link to={item.href || '/'} prefetch>
                        {item.title}
                      </Link>
                    </div>
                  }
                  description={item.description}
                  style={{
                    width: '100%',
                  }}
                />
                <div className={styles.projectItemContent}>
                  <Link to={item.memberLink || '/'} prefetch>
                    {item.member || ''}
                  </Link>
                  {item.updatedAt && (
                    <span className={styles.datetime} title={item.updatedAt}>
                      {dayjs(item.updatedAt).fromNow()}
                    </span>
                  )}
                </div>
              </Card.Grid>
            ))}
          </Card>
          <Card
            styles={{
              body: {
                padding: activitiesLoading ? 16 : 0,
              },
            }}
            variant="borderless"
            className={styles.activeCard}
            title={i18nText("app.dashboard.workplace.ce65c7e0")}
            loading={activitiesLoading}
          >
            <List<ActivitiesType>
              loading={activitiesLoading}
              renderItem={(item) => renderActivities(item)}
              dataSource={activities}
              className={styles.activitiesList}
              size="large"
            />
          </Card>
        </Col>
        <Col xl={8} lg={24} md={24} sm={24} xs={24}>
          <Card
            style={{
              marginBottom: 24,
            }}
            title={i18nText("app.dashboard.workplace.ba570e06")}
            variant="borderless"
          >
            <EditableLinkGroup
              onAdd={() => {}}
              links={links}
              linkElement={Link}
            />
          </Card>
          <Card
            style={{
              marginBottom: 24,
            }}
            variant="borderless"
            title={i18nText("app.dashboard.workplace.f4b66736")}
            loading={data?.radarData?.length === 0}
          >
            <Radar
              height={343}
              data={data?.radarData || []}
              xField="label"
              colorField="name"
              yField="value"
              shapeField="smooth"
              area={{
                style: {
                  fillOpacity: 0.4,
                },
              }}
              axis={{
                y: {
                  gridStrokeOpacity: 0.5,
                },
              }}
              legend={{
                color: {
                  position: 'bottom',
                  layout: { justifyContent: 'center' },
                },
              }}
            />
          </Card>
          <Card
            styles={{
              body: {
                paddingTop: 12,
                paddingBottom: 12,
              },
            }}
            variant="borderless"
            title={i18nText("app.dashboard.workplace.41e8fecf")}
            loading={projectLoading}
          >
            <div className={styles.members}>
              <Row gutter={48}>
                {projectNotice.map((item) => {
                  return (
                    <Col span={12} key={`members-item-${item.id}`}>
                      <Link to={item.memberLink || '/'} prefetch>
                        <Avatar src={item.logo} size="small" />
                        <span className={styles.member}>
                          {item.member.substring(0, 3)}
                        </span>
                      </Link>
                    </Col>
                  );
                })}
              </Row>
            </div>
          </Card>
        </Col>
      </Row>
    </PageContainer>
  );
};
export default Workplace;
