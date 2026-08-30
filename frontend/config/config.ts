// https://umijs.org/config/

import { join } from 'node:path';
import { defineConfig } from '@umijs/max';
import defaultSettings from './defaultSettings';
import proxy from './proxy';

const { UMI_ENV = 'dev' } = process.env;

// Compute commit hash: env vars take precedence, fall back to git at build time
const commitHash =
  process.env.COMMIT_HASH ||
  process.env.CF_PAGES_COMMIT_SHA ||
  (() => {
    try {
      return require('node:child_process')
        .execSync('git rev-parse HEAD', {
          stdio: ['ignore', 'pipe', 'ignore'],
          encoding: 'utf-8',
        })
        .trim();
    } catch {
      return '';
    }
  })();

// 安全读取各包版本信息
const getPackageVersion = (packageName: string): string => {
  try {
    return require(`${packageName}/package.json`).version;
  } catch {
    console.log('读取依赖包出错：', packageName);
    return 'unknown';
  }
};

/**
 * @name PUBLIC_PATH 使用公共路径
 * @description 部署时的路径，如果部署在非根目录下，需要配置这个变量
 * @doc https://umijs.org/docs/api/config#publicpath
 */
const PUBLIC_PATH: string = '/';
const prosemirrorCjs = (packageName: string) =>
  join(__dirname, `../node_modules/prosemirror-${packageName}/dist/index.cjs`);

export default defineConfig({
  alias: {
    '@root': join(__dirname, '..'),
    // Utoopack may otherwise instantiate Tiptap's wrapper and direct
    // ProseMirror/Yjs imports separately in production async chunks.
    '@tiptap/pm/gapcursor': prosemirrorCjs('gapcursor'),
    '@tiptap/pm/model': prosemirrorCjs('model'),
    '@tiptap/pm/state': prosemirrorCjs('state'),
    '@tiptap/pm/tables': prosemirrorCjs('tables'),
    '@tiptap/pm/transform': prosemirrorCjs('transform'),
    '@tiptap/pm/view': prosemirrorCjs('view'),
    'prosemirror-gapcursor': prosemirrorCjs('gapcursor'),
    'prosemirror-model': prosemirrorCjs('model'),
    'prosemirror-state': prosemirrorCjs('state'),
    'prosemirror-tables': prosemirrorCjs('tables'),
    'prosemirror-transform': prosemirrorCjs('transform'),
    'prosemirror-view': prosemirrorCjs('view'),
    yjs: join(__dirname, '../node_modules/yjs/dist/yjs.cjs'),
    /**
     * 兼容性 alias
     * @description 将 child_process 指向空模块，防止浏览器端构建时因
     *              Node.js 内置模块缺失（requestRecordMock.ts 引用）而报错
     *              @utoo/pack-shared 不支持 false 值，需使用字符串路径
     */
    child_process: join(__dirname, 'emptyModule.js'),
  },
  /**
   * @name hash 开启 hash 模式
   * @description 让 build 之后的产物包含 hash 后缀。通常用于增量发布和避免浏览器加载缓存。
   * @doc https://umijs.org/docs/api/config#hash
   */
  hash: true,

  publicPath: PUBLIC_PATH,

  /**
   * @name targets 兼容性设置
   * @description 设置 ie11 不一定完美兼容，需要检查自己使用的所有依赖
   * @doc https://umijs.org/docs/api/config#targets
   */
  // targets: {
  //   ie: 11,
  // },
  /**
   * @name routes 路由的配置，不在路由中引入的文件不会编译
   * @description 只支持 path，component，routes，redirect，wrappers，title 的配置
   * @doc https://umijs.org/docs/guides/routes
   */
  // umi routes: https://umijs.org/docs/routing
  // 注释，从后端获取菜单和路由
  // routes,
  /**
   * @name theme 主题的配置
   * @description 虽然叫主题，但是其实只是 less 的变量设置
   * @doc antd的主题设置 https://ant.design/docs/react/customize-theme-cn
   * @doc umi 的 theme 配置 https://umijs.org/docs/api/config#theme
   */
  // theme: { '@primary-color': '#1DA57A' }
  /**
   * @name moment 的国际化配置
   * @description 如果对国际化没有要求，打开之后能减少js的包大小
   * @doc https://umijs.org/docs/api/config#ignoremomentlocale
   */
  ignoreMomentLocale: true,
  /**
   * @name proxy 代理配置
   * @description 可以让你的本地服务器代理到你的服务器上，这样你就可以访问服务器的数据了
   * @see 要注意以下 代理只能在本地开发时使用，build 之后就无法使用了。
   * @doc 代理介绍 https://umijs.org/docs/guides/proxy
   * @doc 代理配置 https://umijs.org/docs/api/config#proxy
   */
  proxy: proxy[UMI_ENV as keyof typeof proxy],
  /**
   * @name fastRefresh 快速热更新配置
   * @description 一个不错的热更新组件，更新时可以保留 state
   */
  fastRefresh: true,
  /**
   * @name routePrefetch 路由预加载
   * @description 预加载路由资源，提升页面切换速度
   * @doc https://umijs.org/docs/api/config#routePrefetch
   */
  routePrefetch: {},
  /**
   * @name manifest 配置
   * @description 生成资源清单，配合 routePrefetch 使用
   */
  manifest: {},
  //============== 以下都是max的插件配置 ===============
  /**
   * @name model 数据流插件
   * @@doc https://umijs.org/docs/max/data-flow
   */
  model: {},
  /**
   * 一个全局的初始数据流，可以用它在插件之间共享数据
   * @description 可以用来存放一些全局的数据，比如用户信息，或者一些全局的状态，全局初始状态在整个 Umi 项目的最开始创建。
   * @doc https://umijs.org/docs/max/data-flow#%E5%85%A8%E5%B1%80%E5%88%9D%E5%A7%8B%E7%8A%B6%E6%80%81
   */
  initialState: {},
  title: 'Nobody Is Perfect',
  /**
   * @name layout 插件
   * @doc https://umijs.org/docs/max/layout-menu
   */
  layout: {
    locale: true,
    ...defaultSettings,
  },
  /**
   * @name moment2dayjs 插件
   * @description 将项目中的 moment 替换为 dayjs
   * @doc https://umijs.org/docs/max/moment2dayjs
   */
  moment2dayjs: {
    preset: 'antd',
    plugins: ['duration', 'relativeTime'],
  },
  /**
   * @name locale 国际化插件
   * @doc https://umijs.org/docs/max/i18n
   */
  locale: {
    // default zh-CN
    default: 'zh-CN',
    antd: true,
    // default true, when it is true, will use `navigator.language` overwrite default
    baseNavigator: false,
  },
  /**
   * @name antd 插件
   * @description 内置了 babel import 插件
   * @doc https://umijs.org/docs/max/antd#antd
   */
  antd: {
    appConfig: {},
    configProvider: {
      variant: 'filled',
      theme: {
        token: {
          fontFamily: 'AlibabaSans, sans-serif',
        },
      },
    },
  },
  /**
   * @name request 网络请求配置
   * @description 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
   * @doc https://umijs.org/docs/max/request
   */
  request: {},
  /**
   * @name React Query 插件
   * @description 使用 react-query 管理服务端状态
   * @doc https://umijs.org/docs/max/react-query
   */
  reactQuery: {},
  /**
   * @name access 权限插件
   * @description 基于 initialState 的权限插件，必须先打开 initialState
   * @doc https://umijs.org/docs/max/access
   */
  access: {},
  /**
   * @name analytics Google Analytics
   * @description 使用 GA4 (gtag.js) 进行站点分析
   * @doc https://umijs.org/docs/max/analytics
   */
  analytics: {
    ga_v2: 'G-59NF1VHHPF',
  },
  /**
   * @name <head> 中额外的 script
   * @description 配置 <head> 中额外的 script
   */
  headScripts: [
    // 解决首次加载时白屏的问题
    { src: join(PUBLIC_PATH, 'scripts/loading.js'), async: true },
  ],

  //================ pro 插件配置 =================
  /**
   * 插件配置
   * @description 移除 @umijs/request-record，避免生成包含 child_process 的临时文件
   * @reason mock: false 时，request-record 插件仍会生成 requestRecordMock.ts，
   *         该文件引用了 Node.js 专属的 child_process 模块，导致浏览器端构建失败
   */
  plugins: ['@umijs/max-plugin-openapi'],

  /**
   * @name openAPI 插件的配置
   * @description 基于 openapi 的规范生成serve 和mock，能减少很多样板代码
   * @doc https://pro.ant.design/zh-cn/docs/openapi/
   */
  openAPI: [
    {
      requestLibPath: "import { request } from '@umijs/max'",
      // 或者使用在线的版本
      // schemaPath: "https://gw.alipayobjects.com/os/antfincdn/M%24jrzTTYJN/oneapi.json"
      schemaPath: join(__dirname, 'oneapi.json'),
      mock: false,
    },
  ],
  mock: false,
  tailwindcss: {},
  /**
   * utoopack 配置
   * @description
   *   自定义 Turbopack 构建规则
   *   新增：将 @ant-design/x-markdown 的 CSS 文件作为空 JS 模块处理，
   *   避免 Turbopack CSS 管道因不支持现代 CSS 语法（嵌套/@layer）而崩溃
   */
  utoopack: {
    module: {
      rules: {
        '*.md': {
          loaders: [{ loader: join(__dirname, 'md-raw-loader.cjs') }],
          as: '*.js',
        },
        // 将 x-markdown 的 CSS 文件当作空 JS 处理，彻底跳过 Turbopack CSS 管道
        './node_modules/@ant-design/x-markdown/**/*.css': {
          loaders: [{ loader: join(__dirname, 'empty-loader.cjs') }],
          as: '*.js',
        },
        // 新增：将 swagger-ui-dist 的 CSS 文件当作空 JS 处理
        './node_modules/swagger-ui-dist/**/*.css': {
          loaders: [{ loader: join(__dirname, 'empty-loader.cjs') }],
          as: '*.js',
        },
      },
    },
  },
  /**
   * requestRecord 插件配置
   * @description 已禁用：该插件会生成依赖 child_process 的临时文件，与 mock: false 冲突
   *              如需重新启用，请同时在 plugins 数组中恢复 '@umijs/request-record'
   */
  // requestRecord: {},
  exportStatic: {},
  define: {
    'process.env.CI': process.env.CI,
    'process.env.COMMIT_HASH': commitHash,
    'process.env.REACT_APP_GOOGLE_GENERATIVE_AI_API_KEY':
      process.env.REACT_APP_GOOGLE_GENERATIVE_AI_API_KEY,
    'process.env.REACT_APP_DEEPSEEK_API_KEY':
      process.env.REACT_APP_DEEPSEEK_API_KEY,
    'process.env.REACT_APP_OPEN_ROUTER_API_KEY':
      process.env.REACT_APP_OPEN_ROUTER_API_KEY,
    // 读取项目自身版本
    __APP_VERSION__: require('./../package.json').version,
    // 读取 @umijs/max 版本
    __UMI_VERSION__: getPackageVersion('@umijs/max'),
    // 读取 @utoo/pack 版本（内部包，不一定存在，降级返回 unknown）
    __UTOO_VERSION__: getPackageVersion('@utoo/pack'),
  },
} as any);
