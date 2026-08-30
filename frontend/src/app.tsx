import {i18nText} from '@/utils/i18n';
import {LinkOutlined} from '@ant-design/icons';
import type {Settings as LayoutSettings} from '@ant-design/pro-components';
import type {RequestConfig, RunTimeLayoutConfig} from '@umijs/max';
import {history, Link} from '@umijs/max';
import {ThemeProvider} from 'antd-style';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';
import {AliveScope} from 'react-activation';
import {AvatarDropdown, ErrorBoundary, LangDropdown, OfflineBanner, ThemeSwitch,} from '@/components';
import TabsLayout from '@/components/TabsLayout';
import {Toaster} from '@/components/ui/sonner';
import {ThemeContextProvider, useThemeContext} from '@/contexts/ThemeContext';
import IconMap from '@/icons/IconMap';
import {queryCurrentUser} from '@/services/ant-design-pro/base';
import {listRecursiveMenus} from '@/services/ant-design-pro/rbac';
import defaultSettings from '../config/defaultSettings';
import {errorConfig} from './requestErrorConfig';

// Initialize dayjs plugins globally
dayjs.extend(relativeTime);

const isDev = process.env.NODE_ENV === 'development';
const loginPath = '/user/login';

/** 首页路径 */
const homePath = '/HomePage';

/** 根路径集合 */
const rootPaths = ['/', ''];

const loginRoute = {
  path: '/user',
  layout: false,
  routes: [
    {
      name: 'login',
      path: '/user/login',
    },
  ],
};

/**
 * 菜单转换
 * 由于 ant design pro 项目中，图标不支持从后端获取配置后直接转换，所以需要前端加一个映射
 * 配置可参见官网：https://umijs.org/docs/max/layout-menu
 * @param rawMenu 后端的菜单配置信息
 * @param userMenuCodes 前端支持的菜单集合
 */
function transfer(rawMenu: any, userMenuCodes: string[]) {
  if (!userMenuCodes.includes(rawMenu.menuCode)) {
    return null;
  }
  const one: any = {
    id: rawMenu.id,
    name: rawMenu.menuCode,
    path: rawMenu.menuUrl,
    icon: IconMap[rawMenu.icon] || '',
  };
  if (rawMenu.children && rawMenu.children.length > 0) {
    one.routes = rawMenu.children
      .map((subRawMenu: any) => transfer(subRawMenu, userMenuCodes))
      .filter((one: any) => one);
  }
  return one;
}

/**
 * @see https://umijs.org/docs/api/runtime-config#getinitialstate
 * */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
  loading?: boolean;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
  settingDrawerOpen?: boolean;
}> {
  const fetchUserInfo = async () => {
    try {
      if (!localStorage.getItem('user_token')) {
        // 还未登录时，刷新页面，此时，不去获取用户信息
        return undefined;
      }
      const msg = await queryCurrentUser({
        skipErrorHandler: true,
      });
      const user = msg.data;
      // 权限，前后端字段兼容
      user.access = user.authorities;
      user.userid = user.id;
      // TODO 处理主题配置

      return user;
    } catch (_error) {
      const { pathname, search, hash } = history.location;
      history.replace(
        `${loginPath}?redirect=${encodeURIComponent(pathname + search + hash)}`,
      );
    }
    return undefined;
  };

  // 如果不是登录页面，执行
  const { location } = history;

  // 如果是登录页面等，不获取用户信息，直接返回
  if (
    [loginPath, '/user/register', '/user/register-result'].includes(
      location.pathname,
    )
  ) {
    return {
      fetchUserInfo,
      settings: defaultSettings as Partial<LayoutSettings>,
      settingDrawerOpen: false,
    };
  }

  const currentUser = await fetchUserInfo();

  // 已登录且访问根路径，兜底跳转到首页
  if (currentUser && rootPaths.includes(location.pathname)) {
    history.replace(homePath);
  }

  return {
    fetchUserInfo,
    currentUser,
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

// 主题包裹容器
function ThemeWrapper({ children }: { children: React.ReactNode }) {
  const { isDark, themeToken } = useThemeContext();

  return (
    <ThemeProvider
      appearance={isDark ? 'dark' : 'light'}
      theme={{
        token: {
          colorPrimary: themeToken.colorPrimary,
          borderRadius: themeToken.borderRadius,
        },
      }}
    >
      {children}
    </ThemeProvider>
  );
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  // 判断当前是否为用户相关页面（登录/注册等），用于隐藏 Layout 各区域
  const isUserPage = history.location.pathname.startsWith('/user/');

  return {
    // ✅ 登录页隐藏左侧菜单栏
    menuRender: isUserPage ? false : undefined,
    // ✅ 登录页隐藏顶部 header（直接设为 false，而非函数返回 false，避免空白占位）
    headerRender: isUserPage ? false : undefined,
    // ✅ 登录页隐藏底部 Footer
    footerRender: false,
    // ✅ 登录页不渲染右上角操作按钮
    actionsRender: isUserPage
      ? () => []
      : () => [
        <LangDropdown key="lang"/>,
          <ThemeSwitch key="theme-switch" />,
        ],
    // ✅ 登录页不渲染头像区域
    avatarProps: isUserPage
      ? undefined
      : {
          src: initialState?.currentUser?.avatar,
          title: initialState?.currentUser?.userName || 'ProUser',
          render: (_, avatarChildren) => (
            <AvatarDropdown>{avatarChildren}</AvatarDropdown>
          ),
        },
    // TODO RightContent
    menu: {
      params: {
        // 每当 initialState?.currentUser?.userid 发生修改时重新执行 request https://beta-pro.ant.design/docs/advanced-menu-cn
        userId: initialState?.currentUser?.userid,
      },
      request: async () => {
        if (!initialState?.currentUser?.userid) {
          // 没登录返回登录 route，不显示菜单
          return [loginRoute];
        }
        let rawMenus: Array<any>;
        try {
          rawMenus = await listRecursiveMenus({ status: 1 });
        } catch (e) {
          console.log('从后端获取菜单异常', e);
          rawMenus = [];
        }
        const userMenuCodes: string[] = initialState?.currentUser?.menus || [];
        return rawMenus
          .filter((one: any) => userMenuCodes.includes(one.menuCode))
          .map((rawMenu: any) => transfer(rawMenu, userMenuCodes));
      },
    },
    onCollapse: (collapsed) => {
      setInitialState({
        ...initialState,
        settings: { ...initialState.settings, collapsed },
      });
    },
    // defaultCollapsed: true, // 菜单默认折叠
    breakpoint: false, // 用于控制在屏幕小于指定尺寸时，自动收起菜单栏，若需要 defaultCollapsed 配置为 true 使页面默认收起菜单栏的话，必须要设置该值为 false
    // waterMarkProps: {
    // 水印
    //   content: initialState?.currentUser?.userName,
    // },
    onPageChange: () => {
      const { location } = history;

      // 第一步：未登录则跳转到登录页
      if (!initialState?.currentUser && location.pathname !== loginPath) {
        history.replace(
          `${loginPath}?redirect=${encodeURIComponent(
            location.pathname + location.search + location.hash,
          )}`,
        );
        return;
      }
      // 第二步：已登录且访问根路径，跳转到首页
      if (initialState?.currentUser && rootPaths.includes(location.pathname)) {
        history.replace(homePath);
      }
    },
    bgLayoutImgList: [],
    links: isDev
      ? [
          <Link key="openapi" to="/umi/plugin/openapi" target="_blank">
            <LinkOutlined />
            <span>{i18nText("app.common.app.223c3ddf")}</span>
          </Link>,
        ]
      : [],
    // Replace ProLayout's default ErrorBoundary with our offline-aware version,
    // so chunk load errors show friendly messages instead of "Something went wrong."
    ErrorBoundary,
    menuHeaderRender: undefined,
    // 自定义 403 页面
    // unAccessible: <div>unAccessible</div>,

    childrenRender: (children: React.ReactNode, props: any) => {
      // 在登录相关页面不使用 TabsLayout 和 AliveScope
      if (props?.location?.pathname?.startsWith('/user/')) {
        return children;
      }
      return (
        <>
          <TabsLayout />
          {children}
        </>
      );
    },
    ...initialState?.settings,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  baseURL: isDev ? '' : '',
  ...errorConfig,
};

export function rootContainer(container: React.ReactNode) {
  return (
    <ThemeContextProvider>
      <AliveScope>
        <OfflineBanner />
        {/* Toaster 放在最前面，确保在所有页面组件之前完成挂载 */}
        <Toaster />
        <ErrorBoundary>
          <ThemeWrapper>{container}</ThemeWrapper>
        </ErrorBoundary>
      </AliveScope>
    </ThemeContextProvider>
  );
}
