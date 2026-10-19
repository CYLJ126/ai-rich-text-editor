import {createServer} from 'vite';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));
const frontend = path.resolve(root, '../..');
const server = await createServer({
  root,
  configFile: false,
  resolve: {
    alias: [
      {find: '@umijs/max', replacement: path.join(root, 'shim.ts')},
      {find: '@', replacement: path.join(frontend, 'src')},
    ],
  },
  server: {
    host: '127.0.0.1',
    port: 5218,
    strictPort: true,
    fs: {allow: [frontend]},
    proxy: {'/arte': {target: 'http://127.0.0.1:15218'}},
  },
});
await server.listen();
server.printUrls();
