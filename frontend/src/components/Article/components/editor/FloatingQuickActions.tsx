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
import React, {type RefObject, useState} from 'react';
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
      label: '到顶部',
      icon: <VerticalAlignTopOutlined />,
      onClick: () => scrollArticle('top'),
    },
    {
      key: 'bottom',
      label: '到底部',
      icon: <VerticalAlignBottomOutlined />,
      onClick: () => scrollArticle('bottom'),
    },
    {
      key: 'scrollbar',
      label: '内容滚动条',
      icon: showScrollbar ? <EyeInvisibleOutlined /> : <EyeOutlined />,
      onClick: () => onShowScrollbarChange(!showScrollbar),
    },
    {
      key: 'ai',
      label: 'AI Chat',
      icon: <RobotOutlined />,
      onClick: onAskAi,
    },
    {
      key: 'metaInfo',
      label: '文章元数据',
      icon: <FileUnknownOutlined />,
      onClick: () => toggleShowMetaInfo(),
    },
    {
      key: 'history',
      label: '历史版本',
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
        aria-label="文章快捷操作"
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
