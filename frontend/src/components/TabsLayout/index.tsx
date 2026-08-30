import {CloseOutlined} from '@ant-design/icons';
import {history, useIntl, useLocation} from '@umijs/max';
import {Button, Tabs} from 'antd';
import {createStyles} from 'antd-style';
import React, {useEffect, useState} from 'react';
import {useAliveController} from 'react-activation';

const useStyles = createStyles(({ token, css }) => ({
  tabsContainer: css`
    background-color: ${token.colorBgContainer};
    border-bottom: 1px solid ${token.colorBorderSecondary};
    padding: 0 12px;
    position: sticky;
    top: 0;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);

    /* 覆盖 antd Tabs 默认样式，减小整体高度 */
    .ant-tabs-nav {
      margin-bottom: 0 !important;
      &::before {
        border-bottom: none !important;
      }
    }

    /* 标签项整体高度压缩 */
    .ant-tabs-tab {
      padding: 4px 8px !important;
      margin: 0 2px 0 0 !important;
      border-radius: 4px 4px 0 0 !important;
      font-size: 13px !important;
      line-height: 1.4 !important;
      border: 1px solid ${token.colorBorderSecondary} !important;
      background-color: ${token.colorFillAlter} !important;
      transition: all 0.15s ease !important;
      user-select: none;
      &:hover:not(.ant-tabs-tab-active) {
        background-color: ${token.colorFillSecondary} !important;
        border-color: ${token.colorBorder} !important;
        color: ${token.colorText} !important;
      }
    }

    /* 激活标签样式 */
    .ant-tabs-tab-active {
      background-color: ${token.colorBgContainer} !important;
      border-bottom-color: ${token.colorBgContainer} !important;
      border-color: ${token.colorBorderSecondary} !important;
      .ant-tabs-tab-btn {
        color: ${token.colorPrimary} !important;
        font-weight: 500 !important;
        text-shadow: none !important;
      }
    }

    /* 标签文字区域 */
    .ant-tabs-tab-btn {
      font-size: 13px !important;
      line-height: 20px !important;
    }

    /* 去掉 editable-card 新增按钮区域的多余空间 */
    .ant-tabs-nav-add {
      display: none !important;
    }

    /* 墨水条 */
    .ant-tabs-ink-bar {
      display: none !important;
    }

    /* tab 列表区域上边距对齐 */
    .ant-tabs-nav-wrap {
      padding-top: 4px;
    }

    /* 右侧额外内容区域垂直居中 */
    .ant-tabs-extra-content {
      display: flex;
      align-items: center;
      padding: 4px 0;
    }
  `,

  /* 单个标签内容布局 */
  customTab: css`
    display: flex;
    align-items: center;
    gap: 4px;
    max-width: 160px;
  `,
  tabLabel: css`
    font-size: 13px;
    line-height: 20px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 120px;
  `,

  /* 关闭按钮 */
  closeBtn: css`
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 16px !important;
    height: 16px !important;
    min-width: 16px !important;
    padding: 0 !important;
    border-radius: 3px !important;
    color: ${token.colorTextQuaternary} !important;
    flex-shrink: 0;
    transition: all 0.15s ease !important;
    .anticon {
      font-size: 10px !important;
    }
    &:hover {
      color: ${token.colorTextSecondary} !important;
      background-color: ${token.colorFill} !important;
    }
  `,

  /* 右侧操作按钮组 */
  extraBtnGroup: css`
    display: flex;
    align-items: center;
    gap: 4px;
  `,
  extraBtn: css`
    height: 24px !important;
    padding: 0 8px !important;
    font-size: 12px !important;
    border-radius: 4px !important;
    color: ${token.colorTextSecondary} !important;
    border-color: ${token.colorBorderSecondary} !important;
    line-height: 22px !important;
    transition: all 0.15s ease !important;
    &:hover {
      color: ${token.colorPrimary} !important;
      border-color: ${token.colorPrimary} !important;
      background-color: ${token.colorPrimaryBg} !important;
    }
  `,
}));

export interface TabItem {
  key: string;
  label: string;
  pathname: string;
  search?: string;
  closable?: boolean;
}

const HOME_PATH = '/HomePage';

/**
 * 不需要显示 Tab 标签栏的路径前缀列表
 * 匹配规则：pathname === prefix 或 pathname.startsWith(prefix + '/')
 */
const HIDDEN_TAB_PATHS: string[] = [HOME_PATH, '/Learn', '/Personal/GeneralManagement', '/Writing'];

/**
 * 判断当前路径是否应隐藏 Tab 标签栏
 */
function isHiddenTabPath(pathname: string): boolean {
  return HIDDEN_TAB_PATHS.some(
    (prefix) =>
      pathname === prefix || pathname.startsWith(prefix + '/'),
  );
}

/**
 * 国际化翻译，组国际化的键，然后下一步去拿到国际化信息
 *
 * @param path 页面路径
 */
function getMenuIntlInfo(path: string) {
  const pathArr = path.split('/');
  return {
    id: 'menu' + pathArr.join('.'),
    defaultMessage:
      pathArr.length > 0 ? pathArr[pathArr.length - 1] : 'tempPage',
  };
}

const TabsLayout: React.FC = () => {
  const { styles } = useStyles();
  const location = useLocation();
  const { drop, getCachingNodes } = useAliveController();
  const [activeKey, setActiveKey] = useState<string>('');
  const [tabs, setTabs] = useState<TabItem[]>([]);
  const intl = useIntl();

  // 根据路径生成标签页信息
  const generateTabInfo = (pathname: string, search?: string): TabItem => {
    const key = `${pathname}${search || ''}`;
    const menuIntlInfo = getMenuIntlInfo(pathname);
    let label = intl.formatMessage(menuIntlInfo) || 'tempPage';
    // 如果有查询参数，可以在这里处理标签显示
    if (search) {
      const params = new URLSearchParams(search);
      const id = params.get('id');
      if (id) {
        label += ` (${id})`;
      }
    }
    return {
      key,
      label,
      pathname,
      search,
      closable: pathname !== HOME_PATH, // 首页不可关闭
    };
  };

  /**
   * 将新标签插入列表，确保 HomePage 始终在 index 0
   * 规则：
   * 1. 若新标签是 HomePage → 插入到最前面（index 0）
   * 2. 若新标签不是 HomePage → 追加到末尾
   * 3. 若已存在相同 key → 不重复插入，直接返回原数组
   */
  const insertTab = (prevTabs: TabItem[], newTab: TabItem): TabItem[] => {
    // 已存在，直接返回
    const exists = prevTabs.some((t) => t.key === newTab.key);
    if (exists) return prevTabs;

    if (newTab.pathname === HOME_PATH) {
      // HomePage 插入到最前面
      return [newTab, ...prevTabs];
    }

    // 其他页面追加到末尾
    return [...prevTabs, newTab];
  };

  // 监听路由变化，添加或激活标签页
  useEffect(() => {
    const { pathname, search } = location;
    const nodes = getCachingNodes();

    if (pathname.startsWith('/user/') || pathname === '/' || isHiddenTabPath(pathname)) {
      return;
    }

    const tabInfo = generateTabInfo(pathname, search);

    setTabs((prevTabs) => {
      let newTabs = prevTabs;

      // ✅ 刷新场景：tabs 为空或缺少 HomePage 时，自动补充 HomePage 到 index 0
      const hasHomePage = newTabs.some((t) => t.pathname === HOME_PATH);
      if (!hasHomePage) {
        const homeTabInfo = generateTabInfo(HOME_PATH);
        // 无论何时都让 HomePage 在第一个，且始终有
        newTabs = [homeTabInfo, ...newTabs];
      }

      // 再插入当前路径标签（insertTab 内部防止重复）
      return insertTab(newTabs, tabInfo);
    });

    setActiveKey(tabInfo.key);
  }, [location.pathname, location.search]);

  // 标签页切换
  const handleTabChange = (key: string) => {
    const tab = tabs.find((t) => t.key === key);
    if (tab) {
      setActiveKey(key);
      history.push({ pathname: tab.pathname, search: tab.search });
    }
  };

  // 关闭标签页
  const handleTabClose = (key: string, event?: React.MouseEvent) => {
    event?.stopPropagation();
    const tab = tabs.find((t) => t.key === key);
    if (!tab || !tab.closable) return;

    const newTabs = tabs.filter((t) => t.key !== key);
    setTabs(newTabs);
    // 删除缓存
    drop(key).then();
    // 如果关闭的是当前激活的标签页，需要跳转到其他页面
    if (activeKey === key) {
      // ✅ 关闭后优先跳转到 HomePage（始终在 index 0）
      const homeTab = newTabs.find((t) => t.pathname === HOME_PATH);
      const fallbackTab = homeTab ?? newTabs[newTabs.length - 1];
      if (fallbackTab) {
        setActiveKey(fallbackTab.key);
        history.push({
          pathname: fallbackTab.pathname,
          search: fallbackTab.search,
        });
      } else {
        history.push(HOME_PATH);
      }
    }
  };

  // 关闭其他标签页（保留 HomePage + 当前页）
  const handleCloseOthers = (currentKey: string) => {
    const currentTab = tabs.find((t) => t.key === currentKey);
    if (!currentTab) return;

    // ✅ 保留不可关闭的标签（HomePage）+ 当前标签，且保持 HomePage 在首位
    const newTabs = tabs.filter((t) => t.key === currentKey || !t.closable);
    tabs.forEach((tab) => {
      if (tab.key !== currentKey && tab.closable) drop(tab.key).then();
    });

    setTabs(newTabs);
    setActiveKey(currentKey);
    history.push({
      pathname: currentTab.pathname,
      search: currentTab.search,
    });
  };

  // 关闭所有可关闭的标签页
  const handleCloseAll = () => {
    // ✅ 保留不可关闭的标签（HomePage 始终保留在首位）
    const newTabs = tabs.filter((t) => !t.closable);
    // 删除所有可关闭标签页的缓存
    tabs.forEach((tab) => {
      if (tab.closable) drop(tab.key).then();
    });
    setTabs(newTabs);
    // 跳转到首页或第一个不可关闭的标签页
    const firstTab = newTabs[0]; // index 0 就是 HomePage
    if (firstTab) {
      setActiveKey(firstTab.key);
      history.push({ pathname: firstTab.pathname, search: firstTab.search });
    } else {
      history.push(HOME_PATH);
    }
  };

  /** 渲染单个标签 */
  const renderTabLabel = (tab: TabItem) => (
    <div className={styles.customTab}>
      <span className={styles.tabLabel} title={tab.label}>
        {tab.label}
      </span>
      {tab.closable && (
        <Button
          type="text"
          className={styles.closeBtn}
          icon={<CloseOutlined />}
          onClick={(e) => handleTabClose(tab.key, e)}
        />
      )}
    </div>
  );

  const items = tabs.map((tab) => ({
    key: tab.key,
    label: renderTabLabel(tab),
    closable: false, // 我们自己处理关闭逻辑
  }));

  // 如果在登录页面或没有标签，不显示标签栏
  if (tabs.length === 0 || location.pathname.startsWith('/user/') || isHiddenTabPath(location.pathname)) {
    return null;
  }

  return (
    <div className={styles.tabsContainer}>
      <Tabs
        type="editable-card"
        hideAdd
        activeKey={activeKey}
        items={items}
        onChange={handleTabChange}
        tabBarExtraContent={{
          right: (
            <div className={styles.extraBtnGroup}>
              <Button
                className={styles.extraBtn}
                onClick={() => handleCloseOthers(activeKey)}
              >
                关闭其他
              </Button>
              <Button className={styles.extraBtn} onClick={handleCloseAll}>
                关闭所有
              </Button>
            </div>
          ),
        }}
      />
    </div>
  );
};

export default TabsLayout;
