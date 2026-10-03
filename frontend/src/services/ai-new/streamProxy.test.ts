// @vitest-environment node
import {createServer, type Server} from 'node:http';
import {createRequire} from 'node:module';
import type {AddressInfo} from 'node:net';
import type {RequestHandler} from '@umijs/bundler-utils/compiled/express';
import {afterEach, expect, it} from 'vitest';
import proxy from '@root/config/proxy';

const require = createRequire(import.meta.url);
// Use the same compression and proxy implementations as Umi's dev server.
const express: typeof import('@umijs/bundler-utils/compiled/express') = require('@umijs/bundler-utils/compiled/express');
const compression: () => RequestHandler = require('@umijs/bundler-webpack/compiled/compression');
const {createProxy} = require('@umijs/bundler-utils/dist/proxy') as typeof import('@umijs/bundler-utils/dist/proxy');
const servers: Server[] = [];

async function listen(server: Server) {
  servers.push(server);
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.removeListener('error', reject);
      resolve();
    });
  });
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

afterEach(async () => {
  await Promise.all(servers.splice(0).map(server => new Promise<void>(resolve => {
    server.closeAllConnections();
    server.close(() => resolve());
  })));
});

it('delivers an SSE frame through Umi compression before upstream completion and still compresses JSON', async () => {
  const first = `event: model\ndata: ${JSON.stringify({textDelta: '第一段'.repeat(400)})}\n\n`;
  const second = 'event: model\ndata: {"textDelta":"第二段"}\n\n';
  let upstreamEncoding: string | undefined;
  let completed = false;
  let finish = () => {
  };
  const upstream = await listen(createServer((req, res) => {
    upstreamEncoding = req.headers['accept-encoding'];
    if (req.url === '/arte/json') {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({text: 'ordinary response'.repeat(400)}));
      return;
    }
    res.writeHead(200, {'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no'});
    res.write(first);
    // The fallback also makes the original buffering bug fail deterministically.
    const timer = setTimeout(() => finish(), 1500);
    finish = () => {
      clearTimeout(timer);
      completed = true;
      res.end(second);
    };
    res.on('close', () => clearTimeout(timer));
  }));
  const app = express();
  app.use(compression());
  createProxy({'/arte/': {...proxy.dev['/arte/'], target: upstream}}, app);
  const url = await listen(createServer(app));
  const response = await fetch(`${url}/arte/events`, {
    headers: {Accept: 'text/event-stream', 'Accept-Encoding': 'gzip'},
    signal: AbortSignal.timeout(4000),
  });
  const reader = response.body?.getReader();
  expect(reader).toBeDefined();
  const chunk = await reader?.read();
  expect(completed).toBe(false);
  expect(response.headers.get('content-encoding')).toBeNull();
  expect(response.headers.get('cache-control')).toContain('no-transform');
  expect(upstreamEncoding).toBe('identity');
  expect(new TextDecoder().decode(chunk?.value)).toBe(first);
  finish();
  let rest = '';
  while (reader) {
    const next = await reader.read();
    if (next.done) break;
    rest += new TextDecoder().decode(next.value);
  }
  expect(rest).toBe(second);
  const json = await fetch(`${url}/arte/json`, {headers: {'Accept-Encoding': 'gzip'}});
  expect(json.headers.get('content-encoding')).toBe('gzip');
  expect(json.headers.get('cache-control') ?? '').not.toContain('no-transform');
  expect(await json.json()).toEqual({text: 'ordinary response'.repeat(400)});
});
