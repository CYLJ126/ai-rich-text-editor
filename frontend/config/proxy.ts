/**
 * @name 代理的配置
 * @see 在生产环境 代理是无法生效的，所以这里没有生产环境的配置
 * -------------------------------
 * The agent cannot take effect in the production environment
 * so there is no configuration of the production environment
 * For details, please see
 * https://pro.ant.design/docs/deploy
 *
 * @doc https://umijs.org/docs/guides/proxy
 */
export default {
  // 如果需要自定义本地开发服务器  请取消注释按需调整
  dev: {
    // localhost:8000/api/** -> https://preview.pro.ant.design/api/**
    '/arte/': {
      target: 'http://localhost:12636', // 要代理的地址
      changeOrigin: true, // 配置了这个可以从 http 代理到 https；依赖 origin 的功能可能需要这个，比如 cookie
      compress: false, // 关键配置：修复无法查看响应数据问题
      ws: true, // SSE 关键配置：禁用缓冲
      onProxyReq: (proxyReq: any) => {
        // 重写请求头，移除 accept-encoding，禁止后端返回压缩数据
        proxyReq.removeHeader('accept-encoding');
        proxyReq.setHeader('accept-encoding', 'identity');
      },
    },
    '/drawio/': {
      target: 'http://nas.haiqingd.top:8080',
      changeOrigin: true,
      pathRewrite: { '^/drawio': '' },
    },
    proxy: {
      '/deepseek-api': {
        target: 'https://api.deepseek.com',
        changeOrigin: true,
        pathRewrite: { '^/deepseek-api': '' },
      },
    },
  },
  /**
   * @name 详细的代理配置
   * @doc https://github.com/chimurai/http-proxy-middleware
   */
  test: {
    // localhost:8000/api/** -> https://pro-api.ant-design-demo.workers.dev/api/**
    '/api/': {
      target: 'https://pro-api.ant-design-demo.workers.dev',
      changeOrigin: true,
    },
    proxy: {
      '/deepseek-api': {
        target: 'https://api.deepseek.com',
        changeOrigin: true,
        pathRewrite: { '^/deepseek-api': '' },
      },
    },
  },
  pre: {
    '/api/': {
      target: 'your pre url',
      changeOrigin: true,
    },
    proxy: {
      '/deepseek-api': {
        target: 'https://api.deepseek.com',
        changeOrigin: true,
        pathRewrite: { '^/deepseek-api': '' },
      },
    },
  },
};
