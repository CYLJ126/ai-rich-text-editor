import {i18nText} from '@/utils/i18n';
import {CloseCircleOutlined, RightOutlined} from '@ant-design/icons';
import {GridContent} from '@ant-design/pro-components';
import {Button, Card, Result} from 'antd';
import useStyles from './index.style';

export default () => {
  const { styles } = useStyles();
  const Content = (
    <>
      <div className={styles.title}>
        <span>{i18nText("app.examples.fail.d5e431c4")}</span>
      </div>
      <div
        style={{
          marginBottom: 16,
        }}
      >
        <CloseCircleOutlined
          style={{
            marginRight: 8,
          }}
          className={styles.error_icon}
        />
        <span>{i18nText("app.examples.fail.d532d7ab")}</span>
        <Button
          type="link"
          style={{
            marginLeft: 16,
            padding: 0,
          }}
        >
          <span>{i18nText("app.examples.fail.acd92a32")}</span>
          <RightOutlined />
        </Button>
      </div>
      <div>
        <CloseCircleOutlined
          style={{
            marginRight: 8,
          }}
          className={styles.error_icon}
        />
        <span>{i18nText("app.examples.fail.808c47cc")}</span>
        <Button
          type="link"
          style={{
            marginLeft: 16,
            padding: 0,
          }}
        >
          <span>{i18nText("app.examples.fail.34502364")}</span>
          <RightOutlined />
        </Button>
      </div>
    </>
  );
  return (
    <GridContent>
      <Card variant="borderless">
        <Result
          status="error"
          title={i18nText("app.examples.fail.4beef5a6")}
          subTitle={i18nText("app.examples.fail.aa88cbba")}
          extra={
            <Button type="primary">
              <span>{i18nText("app.examples.fail.2ac413c5")}</span>
            </Button>
          }
          style={{
            marginTop: 48,
            marginBottom: 16,
          }}
        >
          {Content}
        </Result>
      </Card>
    </GridContent>
  );
};
