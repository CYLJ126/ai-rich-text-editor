import { i18nText } from '@/utils/i18n';
import {
  EyeInvisibleOutlined,
  EyeOutlined,
  FileUnknownOutlined,
  HistoryOutlined,
  ProductFilled,
  RobotOutlined,
  VerticalAlignBottomOutlined,
  VerticalAlignTopOutlined,
} from '@ant-design/icons';
import React, { type RefObject, useState } from 'react';
import styles from './FloatingQuickActions.less';

export interface FloatingQuickActionsProps {
  containerRef: RefObject<HTMLDivElement | null>;
  showScrollbar: boolean;
  onShowScrollbarChange: (show: boolean) => void;
  toggleShowMetaInfo: () => void;
  onAskAi: () => void;
  onShowHistory: () => void;
}

const getScrollContainers = (container: HTMLDivElement | null) => {
  if (!container) return [];
  return Array.from(
    container.querySelectorAll<HTMLElement>(
      '.tiptap-editor-content, #raw-text',
    ),
  ).filter((element) => element.offsetParent !== null);
};

/**
 * 文章浮动操作栏
 * 鼠标悬浮于右下角图标时，展示快捷操作菜单
 */
export const FloatingQuickActions: React.FC<FloatingQuickActionsProps> = ({
  containerRef,
  showScrollbar,
  onShowScrollbarChange,
  toggleShowMetaInfo,
  onAskAi,
  onShowHistory,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);

  const scrollArticle = (position: 'top' | 'bottom') => {
    getScrollContainers(containerRef.current).forEach((element) => {
      element.scrollTo({
        top: position === 'top' ? 0 : element.scrollHeight,
        behavior: 'smooth',
      });
    });
  };

  const menuItems = [
    {
      key: 'top',
      label: i18nText('app.article.editor.floatingquickactions.b19f6435'),
      icon: <VerticalAlignTopOutlined />,
      onClick: () => scrollArticle('top'),
    },
    {
      key: 'bottom',
      label: i18nText('app.article.editor.floatingquickactions.c2376a49'),
      icon: <VerticalAlignBottomOutlined />,
      onClick: () => scrollArticle('bottom'),
    },
    {
      key: 'scrollbar',
      label: i18nText('app.article.editor.floatingquickactions.aa525073'),
      icon: showScrollbar ? <EyeInvisibleOutlined /> : <EyeOutlined />,
      onClick: () => onShowScrollbarChange(!showScrollbar),
    },
    {
      key: 'ai',
      label: i18nText('app.ai.chat'),
      icon: <RobotOutlined />,
      onClick: onAskAi,
    },
    {
      key: 'metaInfo',
      label: i18nText('app.article.editor.floatingquickactions.5cfffe28'),
      icon: <FileUnknownOutlined />,
      onClick: () => toggleShowMetaInfo(),
    },
    {
      key: 'history',
      label: i18nText('app.article.editor.floatingquickactions.9d941b9b'),
      icon: <HistoryOutlined />,
      onClick: onShowHistory,
    },
  ];

  return (
    <div
      className={`${styles.articleFloatingActions} ${
        menuOpen ? styles.articleFloatingActionsOpen : ''
      }`}
      onMouseLeave={() => setMenuOpen(false)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          setMenuOpen(false);
        }
      }}
    >
      <div className={styles.articleFloatingMenu} role="menu">
        {menuItems.map((item) => (
          <button
            key={item.key}
            type="button"
            className={styles.articleFloatingMenuItem}
            onClick={item.onClick}
            role="menuitem"
          >
            {item.icon}
            <span>{item.label}</span>
          </button>
        ))}
      </div>
      <button
        type="button"
        className={styles.articleFloatingTrigger}
        aria-label={i18nText(
          'app.article.editor.floatingquickactions.d0d913a9',
        )}
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onMouseEnter={() => setMenuOpen(true)}
        onFocus={() => setMenuOpen(true)}
      >
        <ProductFilled />
      </button>
    </div>
  );
};
