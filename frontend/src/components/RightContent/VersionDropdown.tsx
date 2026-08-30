import {i18nText} from '@/utils/i18n';
import { ForkOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { Button } from 'antd';
import HeaderDropdown from '../HeaderDropdown';
import useHeaderActionStyles from './style';

const versionItems: MenuProps['items'] = [
  { key: 'https://v5-pro.ant.design', label: 'v5' },
  { key: 'https://v4-pro.ant.design', label: 'v4' },
  { key: 'https://v2-pro.ant.design', label: 'v2' },
  { key: 'https://v1-pro.ant.design', label: 'v1' },
];

const onVersionClick: MenuProps['onClick'] = ({ key }) => {
  window.open(key, '_blank', 'noopener,noreferrer');
};

export const VersionDropdown: React.FC = () => {
  const { styles } = useHeaderActionStyles();
  return (
    <HeaderDropdown
      placement="bottomRight"
      arrow
      menu={{
        selectedKeys: [],
        onClick: onVersionClick,
        items: versionItems,
        style: { minWidth: 100 },
      }}
    >
      <Button type="text" className={styles.action} aria-label={i18nText("app.common.rightcontent.versiondropdown.6ea94a4e")}>
        <ForkOutlined />
      </Button>
    </HeaderDropdown>
  );
};
