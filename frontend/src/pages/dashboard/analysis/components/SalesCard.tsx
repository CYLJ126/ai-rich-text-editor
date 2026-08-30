import {i18nText} from '@/utils/i18n';
import { Column } from '@ant-design/plots';
import { Button, Card, Col, DatePicker, Row, Tabs } from 'antd';
import type { RangePickerProps } from 'antd/es/date-picker';
import { formatNumber } from '@/utils/format';
import type { DataItem } from '../data.d';
import useStyles from '../style.style';

export type TimeType = 'today' | 'week' | 'month' | 'year';
const { RangePicker } = DatePicker;

const rankingListData: {
  title: string;
  total: number;
}[] = [];

for (let i = 0; i < 7; i += 1) {
  rankingListData.push({
    title: i18nText("app.dashboard.components.salescard.0052ad23", {value0: i}),
    total: 323234,
  });
}

const SalesCard = ({
  rangePickerValue,
  salesData,
  isActive,
  handleRangePickerChange,
  loading,
  selectDate,
}: {
  rangePickerValue: RangePickerProps['value'];
  isActive: (key: TimeType) => string;
  salesData: DataItem[];
  loading: boolean;
  handleRangePickerChange: RangePickerProps['onChange'];
  selectDate: (key: TimeType) => void;
}) => {
  const { styles } = useStyles();
  return (
    <Card
      loading={loading}
      variant="borderless"
      styles={{
        body: {
          padding: loading ? 24 : 0,
        },
      }}
    >
      <Tabs
        className={styles.salesCard}
        tabBarExtraContent={
          <div className={styles.salesExtraWrap}>
            <div className={styles.salesExtra}>
              <Button
                type="text"
                className={isActive('today')}
                onClick={() => selectDate('today')}
              >
                {i18nText("app.dashboard.components.salescard.a18b7079")}
              </Button>
              <Button
                type="text"
                className={isActive('week')}
                onClick={() => selectDate('week')}
              >
                {i18nText("app.dashboard.components.salescard.ae69c84d")}
              </Button>
              <Button
                type="text"
                className={isActive('month')}
                onClick={() => selectDate('month')}
              >
                {i18nText("app.dashboard.components.salescard.96d011e8")}
              </Button>
              <Button
                type="text"
                className={isActive('year')}
                onClick={() => selectDate('year')}
              >
                {i18nText("app.dashboard.components.salescard.dfef68fd")}
              </Button>
            </div>
            <RangePicker
              value={rangePickerValue}
              onChange={handleRangePickerChange}
              variant="filled"
              style={{
                width: 256,
              }}
            />
          </div>
        }
        size="large"
        tabBarStyle={{
          marginBottom: 24,
        }}
        items={[
          {
            key: 'sales',
            label: i18nText("app.dashboard.components.salescard.5437341a"),
            children: (
              <Row>
                <Col xl={16} lg={12} md={12} sm={24} xs={24}>
                  <div className={styles.salesBar}>
                    <Column
                      height={300}
                      data={salesData}
                      xField="x"
                      yField="y"
                      paddingBottom={12}
                      axis={{
                        x: {
                          title: false,
                        },
                        y: {
                          title: false,
                          gridLineDash: null,
                          gridStroke: '#ccc',
                        },
                      }}
                      scale={{
                        x: { paddingInner: 0.4 },
                      }}
                      tooltip={{
                        name: i18nText("app.dashboard.components.salescard.749b8393"),
                        channel: 'y',
                      }}
                    />
                  </div>
                </Col>
                <Col xl={8} lg={12} md={12} sm={24} xs={24}>
                  <div className={styles.salesRank}>
                    <h4 className={styles.rankingTitle}>{i18nText("app.dashboard.components.salescard.a8f8432e")}</h4>
                    <ul className={styles.rankingList}>
                      {rankingListData.map((item, i) => (
                        <li key={item.title}>
                          <span
                            className={`${styles.rankingItemNumber} ${
                              i < 3 ? styles.rankingItemNumberActive : ''
                            }`}
                          >
                            {i + 1}
                          </span>
                          <span
                            className={styles.rankingItemTitle}
                            title={item.title}
                          >
                            {item.title}
                          </span>
                          <span>{formatNumber(item.total)}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </Col>
              </Row>
            ),
          },
          {
            key: 'views',
            label: i18nText("app.dashboard.components.salescard.eeabc81d"),
            children: (
              <Row>
                <Col xl={16} lg={12} md={12} sm={24} xs={24}>
                  <div className={styles.salesBar}>
                    <Column
                      height={300}
                      data={salesData}
                      xField="x"
                      yField="y"
                      paddingBottom={12}
                      axis={{
                        x: {
                          title: false,
                        },
                        y: {
                          title: false,
                        },
                      }}
                      scale={{
                        x: { paddingInner: 0.4 },
                      }}
                      tooltip={{
                        name: i18nText("app.dashboard.components.salescard.eeabc81d"),
                        channel: 'y',
                      }}
                    />
                  </div>
                </Col>
                <Col xl={8} lg={12} md={12} sm={24} xs={24}>
                  <div className={styles.salesRank}>
                    <h4 className={styles.rankingTitle}>{i18nText("app.dashboard.components.salescard.0fa2ef0c")}</h4>
                    <ul className={styles.rankingList}>
                      {rankingListData.map((item, i) => (
                        <li key={item.title}>
                          <span
                            className={`${
                              i < 3
                                ? styles.rankingItemNumberActive
                                : styles.rankingItemNumber
                            }`}
                          >
                            {i + 1}
                          </span>
                          <span
                            className={styles.rankingItemTitle}
                            title={item.title}
                          >
                            {item.title}
                          </span>
                          <span>{formatNumber(item.total)}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </Col>
              </Row>
            ),
          },
        ]}
      />
    </Card>
  );
};
export default SalesCard;
