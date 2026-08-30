import {i18nText} from '@/utils/i18n';
import {DingdingOutlined} from '@ant-design/icons';
import {GridContent} from '@ant-design/pro-components';
import {Button, Card, Descriptions, Result, Steps} from 'antd';
import React from 'react';
import useStyles from './index.style';

const descriptionItems = [
  { key: 'id', label: i18nText("app.examples.success.ea80fdc8"), children: '23421' },
  { key: 'owner', label: i18nText("app.examples.success.aa69f004"), children: i18nText("app.examples.success.eb295135") },
  { key: 'time', label: i18nText("app.examples.success.43f86710"), children: '2016-12-12 ~ 2017-12-12' },
];

const extra = (
  <>
    <Button type="primary">{i18nText("app.examples.success.74b566b9")}</Button>
    <Button>{i18nText("app.examples.success.94337d28")}</Button>
    <Button>{i18nText("app.examples.success.77c307fb")}</Button>
  </>
);

const Success: React.FC = () => {
  const { styles } = useStyles();
  const desc1 = (
    <div className={styles.title}>
      <div
        style={{
          margin: '8px 0 4px',
        }}
      >
        <span>{i18nText("app.examples.success.eb295135")}</span>
        <DingdingOutlined
          style={{
            marginLeft: 8,
            color: '#00A0E9',
          }}
        />
      </div>
      <div>2016-12-12 12:32</div>
    </div>
  );
  const desc2 = (
    <div
      style={{
        fontSize: 12,
      }}
      className={styles.title}
    >
      <div
        style={{
          margin: '8px 0 4px',
        }}
      >
        <span>{i18nText("app.examples.success.7a9b6d03")}</span>
        <Button type="link" style={{padding: 0}}>
          <DingdingOutlined
            style={{
              color: '#00A0E9',
              marginLeft: 8,
            }}
          />
          <span>{i18nText("app.examples.success.7cee48fc")}</span>
        </Button>
      </div>
    </div>
  );
  const content = (
    <>
      <Descriptions title={i18nText("app.examples.success.1f94f3b6")} items={descriptionItems} />
      <br />
      <Steps
        type="dot"
        current={1}
        items={[
          {
            title: (
              <span
                style={{
                  fontSize: 14,
                }}
              >
                {i18nText("app.examples.success.373aa34b")}
              </span>
            ),
            content: desc1,
          },
          {
            title: (
              <span
                style={{
                  fontSize: 14,
                }}
              >
                {i18nText("app.examples.success.0ff9caaf")}
              </span>
            ),
            content: desc2,
          },
          {
            title: (
              <span
                style={{
                  fontSize: 14,
                }}
              >
                {i18nText("app.examples.success.6d95480d")}
              </span>
            ),
          },
          {
            title: (
              <span
                style={{
                  fontSize: 14,
                }}
              >
                {i18nText("app.examples.success.8e5de35e")}
              </span>
            ),
          },
        ]}
      />
    </>
  );
  return (
    <GridContent>
      <Card variant="borderless">
        <Result
          status="success"
          title={i18nText("app.examples.success.5989a176")}
          subTitle={i18nText("app.examples.success.8a5b98c8")}
          extra={extra}
          style={{
            marginBottom: 16,
          }}
        >
          {content}
        </Result>
      </Card>
    </GridContent>
  );
};

export default Success;
