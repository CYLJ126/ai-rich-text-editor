import {i18nText} from '@/utils/i18n';
import {BookOutlined} from '@ant-design/icons';
import {history} from '@umijs/max';
import {Button, Tooltip} from 'antd';
import React from 'react';
import {LangDropdown} from './LangDropdown';
import useHeaderActionStyles from './style';
import {VersionDropdown} from './VersionDropdown';

export const DocLink: React.FC = () => {
  const {styles} = useHeaderActionStyles();
  return (
    <Tooltip title={i18nText("app.common.rightcontent.9eef3df3")}>
      <Button
        type="text"
        className={styles.action}
        icon={<BookOutlined/>}
        aria-label={i18nText("app.common.rightcontent.9eef3df3")}
        onClick={() => {
          history.push('/welcome');
        }}
      />
    </Tooltip>
  );
};

export {LangDropdown, VersionDropdown};
