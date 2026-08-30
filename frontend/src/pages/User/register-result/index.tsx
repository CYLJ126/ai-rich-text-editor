import {i18nText} from '@/utils/i18n';
import {Link, useSearchParams} from '@umijs/max';
import {Button, Result} from 'antd';
import React from 'react';
import useStyles from './style.style';

const RegisterResult: React.FC<Record<string, unknown>> = () => {
  const { styles } = useStyles();
  const [params] = useSearchParams();

  const actions = (
    <div className={styles.actions}>
      <Button size="large" type="primary">
        <span>{i18nText("app.user.registerresult.691bfd2a")}</span>
      </Button>
      <Link to="/" prefetch>
        <Button size="large">{i18nText("app.user.registerresult.1b7f5da1")}</Button>
      </Link>
    </div>
  );

  const email = params?.get('account') || 'AntDesign@example.com';
  return (
    <Result
      className={styles.registerResult}
      status="success"
      title={
        <div className={styles.title}>
          <span>{i18nText("app.user.registerresult.d7245add")}{email} {i18nText("app.user.registerresult.b48e5b4c")}</span>
        </div>
      }
      subTitle={i18nText("app.user.registerresult.383d3f65")}
      extra={actions}
    />
  );
};
export default RegisterResult;
