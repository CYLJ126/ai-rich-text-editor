import {createServer} from 'vite';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));
const frontend = path.resolve(root, '../..');
const target = process.env.ARTE_SEARCH_S5_HTTP ?? 'http://127.0.0.1:15145';
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(target)) throw new Error('Only isolated loopback test bridge is allowed');
const server = await createServer({
  root, configFile: false, resolve: {
    alias: [
      {find: '@umijs/max', replacement: path.join(root, 'shim.ts')}, {
        find: '@',
        replacement: path.join(frontend, 'src')
      },
      ...['react', 'react-dom', 'antd'].map(find => ({find, replacement: path.join(frontend, 'node_modules', find)}))
    ]
  }, server: {host: '127.0.0.1', port: 5143, strictPort: true, fs: {allow: [frontend]}, proxy: {'/arte': {target}}}
});
await server.listen();
server.printUrls();
