import { CheckOutlined, GlobalOutlined } from '@ant-design/icons';
import { getAllLocales, getLocale, setLocale } from '@umijs/max';
import type { MenuProps } from 'antd';
import { Button } from 'antd';
import { useMemo } from 'react';
import { i18nText } from '@/utils/i18n';
import HeaderDropdown from '../HeaderDropdown';
import useHeaderActionStyles from './style';

const localeLabelMap: Record<string, { emoji: string; label: string }> = {
  'zh-CN': {
    emoji: '🇨🇳',
    label: i18nText('app.common.rightcontent.langdropdown.065e2d15'),
  },
  'zh-TW': {
    emoji: '🇭🇰',
    label: i18nText('app.common.rightcontent.langdropdown.04240d81'),
  },
  'en-US': { emoji: '🇺🇸', label: 'English' },
};

const onLangClick: MenuProps['onClick'] = ({ key }) => {
  if (key.startsWith('lang-')) {
    setLocale(key.replace('lang-', ''), true);
  }
};

export const LangDropdown: React.FC = () => {
  const { styles } = useHeaderActionStyles();
  const allLocales = useMemo(() => getAllLocales(), []);
  const currentLocale = getLocale();
  const supportLocales = allLocales.filter((l) => l in localeLabelMap);

  if (supportLocales.length <= 1) {
    return null;
  }

  const langItems: MenuProps['items'] = supportLocales.map((locale) => ({
    key: `lang-${locale}`,
    icon:
      locale === currentLocale ? (
        <CheckOutlined style={{ color: '#52c41a' }} />
      ) : (
        <span style={{ display: 'inline-block', width: 14 }} />
      ),
    label: `${localeLabelMap[locale]?.emoji ?? ''} ${localeLabelMap[locale]?.label ?? locale}`,
  }));

  return (
    <HeaderDropdown
      placement="bottomRight"
      arrow
      menu={{
        selectedKeys: [`lang-${currentLocale}`],
        onClick: onLangClick,
        items: langItems,
        style: { minWidth: 180 },
      }}
    >
      <Button
        type="text"
        className={styles.action}
        aria-label={i18nText('app.common.rightcontent.langdropdown.0860a9ed')}
      >
        <GlobalOutlined />
      </Button>
    </HeaderDropdown>
  );
};
