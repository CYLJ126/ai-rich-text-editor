import { i18nText } from '@/utils/i18n';
import { Link } from '@umijs/max';
import { Button, Card, Result } from 'antd';

export default () => (
  <Card variant="borderless">
    <Result
      status="500"
      title="500"
      subTitle={i18nText('app.exception.500.description')}
      extra={
        <Link to="/" prefetch>
          <Button type="primary">{i18nText('app.common.backHome')}</Button>
        </Link>
      }
    />
  </Card>
);
