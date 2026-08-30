import {createStyles} from 'antd-style';
import React, {useEffect, useState} from 'react';

// ─── 侧边栏面板类型 ───
export interface SidePanel {
  /** 唯一标识 */
  id: string;
  /** 显示名称 */
  name: string;
  /** 面板内容组件 */
  component: React.ReactNode;
  /** 面板图标（可选） */
  icon?: React.ReactNode;
  /** 是否禁用内容区域的内边距，默认 false。
   *  传入已自带内边距的组件（如 CatalogTreeSidebar）时设为 true */
  noPadding?: boolean;
}

// ─── 侧边栏组件属性 ───
export interface RightSidebarProps {
  /** 面板数组方式传入 */
  panels?: SidePanel[];
  /** children 方式传入（单个面板，无 Tab） */
  children?: React.ReactNode;
  /** 默认激活的面板 id */
  defaultActiveId?: string;
  /** 外部控制的激活面板 id */
  activeId?: string;
  /** 激活面板变化回调 */
  onActiveIdChange?: (activeId: string) => void;
}

const useStyles = createStyles(({ token, css }) => ({
  container: css`
    display: flex;
    flex-direction: column;
    /* 撑满父容器宽高 */
    width: 100%;
    height: 100%;
    overflow: hidden;
    background: ${token.colorBgContainer};
  `,

  tabBar: css`
    // 不知道 Tab 栏为什么会短15px，所以这里加15px
    width: calc(100% + 15px);
    height: 33px;
    padding-left: 7px;
    display: flex;
    align-items: center;
    gap: 2px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
    flex-shrink: 0;
    overflow-x: auto;

    /* 滚动条细化 */
    &::-webkit-scrollbar {
      height: 5px;
    }
    &::-webkit-scrollbar-thumb {
      background: ${token.colorFill};
      border-radius: 3px;
    }
  `,

  tabItem: css`
    height: 26px;
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 3px;
    font-size: 13px;
    cursor: pointer;
    color: ${token.colorTextSecondary};
    white-space: nowrap;
    transition: all 0.2s;
    user-select: none;
    position: relative;
    bottom: -1px;

    &:hover {
      color: ${token.colorText};
      background: ${token.colorFillTertiary};
    }
  `,

  tabItemActive: css`
    color: ${token.colorPrimary};
    background: ${token.colorBgContainer};
    border-color: ${token.colorBorderSecondary};
    border-bottom-color: ${token.colorBgContainer};
    font-weight: 500;

    &:hover {
      color: ${token.colorPrimary};
      background: ${token.colorBgContainer};
    }
  `,

  // 内容区：撑满剩余高度，宽度100%，默认无 padding
  tabContent: css`
    flex: 1;
    width: 100%;
    min-width: 0;
    overflow: auto;
    box-sizing: border-box;
  `,

  // 有 padding 的内容区变体
  tabContentPadded: css`
    padding: 8px;
  `,
}));

const RightSidebar: React.FC<RightSidebarProps> = ({
                                    panels,
                                    children,
                                    defaultActiveId,
                                    activeId,
                                    onActiveIdChange,
                                  }) => {
  const { styles, cx } = useStyles();

  const [innerActiveId, setInnerActiveId] = useState<string>(
    () => activeId ?? defaultActiveId ?? panels?.[0]?.id ?? '',
  );

  useEffect(() => {
    if (activeId) {
      setInnerActiveId(activeId);
    }
  }, [activeId]);

  const handleTabClick = (nextActiveId: string) => {
    setInnerActiveId(nextActiveId);
    onActiveIdChange?.(nextActiveId);
  };

  // ── children 模式：无 Tab，直接渲染 ──
  if (!panels || panels.length === 0) {
    return (
      <div className={styles.container}>
        <div className={cx(styles.tabContent, styles.tabContentPadded)}>
          {children}
        </div>
      </div>
    );
  }

  // ── panels 模式：渲染 Tab 栏 + 内容 ──
  const activePanel = panels.find((p) => p.id === innerActiveId) ?? panels[0];

  return (
    <div className={styles.container}>
      {/* Tab 栏（多于 1 个面板才显示） */}
      {panels.length > 1 && (
        <div className={styles.tabBar}>
          {panels.map((panel) => (
            <div
              key={panel.id}
              className={cx(
                styles.tabItem,
                panel.id === activePanel.id && styles.tabItemActive,
              )}
              onClick={() => handleTabClick(panel.id)}
            >
              {panel.icon && <span>{panel.icon}</span>}
              <span>{panel.name}</span>
            </div>
          ))}
        </div>
      )}

      {/* 全部面板挂载，display 切换避免状态丢失。用 React.memo 包裹内容容器防止不必要重渲染。 */}
      {panels.map((panel) => (
        <div
          key={panel.id} // key 稳定（panel.id 不变），div 不会卸载重建
          className={cx(
            styles.tabContent,
            // noPadding=true（如 CatalogTreeSidebar）不加内边距
            !panel.noPadding && styles.tabContentPadded,
          )}
          style={{
            display: panel.id === activePanel.id ? 'flex' : 'none',
            flexDirection: 'column',
          }}
        >
          {panel.component}
        </div>
      ))}
    </div>
  );
}

export default RightSidebar;
