import type {ClientRequest, IncomingMessage} from 'node:http';

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
      ws: true, // 支持 WebSocket 升级，与 SSE 响应缓冲无关
      onProxyReq: (proxyReq: ClientRequest) => {
        // 禁止上游压缩；浏览器的 Accept-Encoding 仍会触发 Umi 自身的 gzip。
        proxyReq.setHeader('accept-encoding', 'identity');
      },
      onProxyRes: (proxyRes: IncomingMessage) => {
        if (proxyRes.headers['content-type']?.toLowerCase().startsWith('text/event-stream')) {
          // Umi 的 Express compression 会尊重 no-transform，逐帧透传 SSE。
          proxyRes.headers['cache-control'] = 'no-store, no-transform';
          proxyRes.headers['x-accel-buffering'] = 'no';
        }
      },
    },
    '/drawio/': {
      target: 'https://app.diagrams.net/',
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
