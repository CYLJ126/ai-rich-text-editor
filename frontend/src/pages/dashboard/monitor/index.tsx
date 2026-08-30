import {i18nText} from '@/utils/i18n';
import { Gauge, Liquid, WordCloud } from '@ant-design/plots';
import { GridContent } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { Card, Col, Progress, Row, Skeleton, Statistic } from 'antd';
import { type FC, lazy, Suspense } from 'react';
import { formatNumber } from '@/utils/format';
import ActiveChart from './components/ActiveChart';
import { queryTags } from './service';
import useStyles from './style.style';

const MonitorMap = lazy(() => import('./components/Map'));

const deadline = Date.now() + 1000 * 60 * 60 * 24 * 2 + 1000 * 30; // Moment is also OK

const Monitor: FC = () => {
  const { styles } = useStyles();
  const { isLoading: loading, data } = useQuery({
    queryKey: ['monitor-tags'],
    queryFn: () => queryTags().then((res) => res.data),
  });
  const wordCloudData = (data?.list || []).map((item) => {
    return {
      id: +Date.now(),
      word: item.name,
      weight: item.value,
    };
  });
  return (
    <GridContent>
      <Row gutter={24}>
        <Col
          xl={18}
          lg={24}
          md={24}
          sm={24}
          xs={24}
          style={{
            marginBottom: 24,
            display: 'flex',
          }}
        >
          <Card
            title={i18nText("app.dashboard.monitor.c228b605")}
            variant="borderless"
            style={{ flex: 1 }}
            styles={{
              body: { display: 'flex', flexDirection: 'column', flex: 1 },
            }}
          >
            <Row>
              <Col md={6} sm={12} xs={24}>
                <Statistic
                  title={i18nText("app.dashboard.monitor.211e2617")}
                  suffix={i18nText("app.dashboard.monitor.11ed5995")}
                  value={formatNumber(124543233)}
                />
              </Col>
              <Col md={6} sm={12} xs={24}>
                <Statistic title={i18nText("app.dashboard.monitor.d30554b9")} value="92%" />
              </Col>
              <Col md={6} sm={12} xs={24}>
                <Statistic.Timer
                  type="countdown"
                  title={i18nText("app.dashboard.monitor.f6f41cfd")}
                  value={deadline}
                  format="HH:mm:ss:SSS"
                />
              </Col>
              <Col md={6} sm={12} xs={24}>
                <Statistic
                  title={i18nText("app.dashboard.monitor.10001bb0")}
                  suffix={i18nText("app.dashboard.monitor.11ed5995")}
                  value={formatNumber(234)}
                />
              </Col>
            </Row>
            <div className={styles.mapChart}>
              <Suspense
                fallback={
                  <Skeleton.Node
                    active
                    style={{ width: '100%', height: 356 }}
                  />
                }
              >
                <MonitorMap />
              </Suspense>
            </div>
          </Card>
        </Col>
        <Col xl={6} lg={24} md={24} sm={24} xs={24}>
          <Card
            title={i18nText("app.dashboard.monitor.f3550187")}
            style={{
              marginBottom: 24,
            }}
            variant="borderless"
          >
            <ActiveChart />
          </Card>
          <Card
            title={i18nText("app.dashboard.monitor.df440681")}
            style={{
              marginBottom: 24,
            }}
            styles={{
              body: {
                textAlign: 'center',
              },
            }}
            variant="borderless"
          >
            <Gauge
              height={180}
              data={
                {
                  target: 80,
                  total: 100,
                  name: 'score',
                  thresholds: [20, 40, 60, 80, 100],
                } as any
              }
              padding={-16}
              style={{
                textContent: () => i18nText("app.dashboard.monitor.e9153c01"),
              }}
              meta={{
                color: {
                  range: [
                    '#6395FA',
                    '#62DAAB',
                    '#657798',
                    '#F7C128',
                    '#1F8718',
                  ],
                },
              }}
            />
          </Card>
        </Col>
      </Row>
      <Row gutter={24}>
        <Col
          xl={12}
          lg={24}
          sm={24}
          xs={24}
          style={{
            marginBottom: 24,
          }}
        >
          <Card title={i18nText("app.dashboard.monitor.e4f04a60")} variant="borderless">
            <Row
              style={{
                padding: '16px 0',
              }}
            >
              <Col span={8}>
                <Progress type="dashboard" percent={75} />
              </Col>
              <Col span={8}>
                <Progress type="dashboard" percent={48} />
              </Col>
              <Col span={8}>
                <Progress type="dashboard" percent={33} />
              </Col>
            </Row>
          </Card>
        </Col>
        <Col
          xl={6}
          lg={12}
          sm={24}
          xs={24}
          style={{
            marginBottom: 24,
          }}
        >
          <Card
            title={i18nText("app.dashboard.monitor.f65e1e2c")}
            loading={loading}
            variant="borderless"
            styles={{
              body: {
                overflow: 'hidden',
              },
            }}
          >
            <WordCloud
              data={wordCloudData}
              height={162}
              textField="word"
              colorField="word"
              layout={{ spiral: 'rectangular', fontSize: [10, 20] }}
            />
          </Card>
        </Col>
        <Col
          xl={6}
          lg={12}
          sm={24}
          xs={24}
          style={{
            marginBottom: 24,
          }}
        >
          <Card
            title={i18nText("app.dashboard.monitor.6e219e6e")}
            styles={{
              body: {
                textAlign: 'center',
                fontSize: 0,
              },
            }}
            variant="borderless"
          >
            <Liquid height={160} percent={0.35} />
          </Card>
        </Col>
      </Row>
    </GridContent>
  );
};
export default Monitor;
