import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Alert, Card, Typography } from 'antd';
import React from 'react';
import { i18nText } from '@/utils/i18n';

const Admin: React.FC = () => {
  const intl = useIntl();
  return (
    <PageContainer
      content={intl.formatMessage({
        id: 'pages.admin.subPage.title',
        defaultMessage: 'This page can only be viewed by admin',
      })}
    >
      <Card>
        <Alert
          title={intl.formatMessage({
            id: 'pages.welcome.alertMessage',
            defaultMessage:
              'Faster and stronger heavy-duty components have been released.',
          })}
          type="success"
          showIcon
          banner
          style={{
            margin: -12,
            marginBottom: 48,
          }}
        />
        <Typography.Title level={2} style={{ textAlign: 'center' }}>
          <img
            alt=""
            aria-hidden="true"
            src="/logo.svg"
            style={{ width: 32, height: 32, marginRight: 8, verticalAlign: -6 }}
          />
          AI Rich Text Editor {i18nText('app.admin.you')}
        </Typography.Title>
      </Card>
      <p style={{ textAlign: 'center', marginTop: 24 }}>
        {i18nText('app.admin.morePages')}{' '}
        <a
          href="https://pro.ant.design/docs/block-cn"
          target="_blank"
          rel="noopener noreferrer"
        >
          {i18nText('app.admin.useBlock')}
        </a>
        。
      </p>
    </PageContainer>
  );
};

export default Admin;
