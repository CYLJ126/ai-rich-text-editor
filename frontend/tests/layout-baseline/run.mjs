import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdir, mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync, spawn} from 'node:child_process';
import {createServer} from 'vite';

// 真实 Splitter、ResizeObserver 和 CSS；不装载应用配置或访问业务服务。
const directory = dirname(fileURLToPath(import.meta.url));
const frontend = resolve(directory, '../..');
const output = resolve(process.env.LAYOUT_OUTPUT_DIR ?? join(tmpdir(), 'arte-layout-baseline'));
const profile = await mkdtemp(join(tmpdir(), 'arte-layout-chrome-'));
const chromePath = process.env.LAYOUT_CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const pending = new Map();
let chrome, server, socket, sequence = 0;
const wait = async read => {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await pause(25);
  }
  throw new Error('Layout acceptance timed out');
};

function command(method, params = {}) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out`));
    }, 30000);
    pending.set(id, {resolve, reject, timer});
    socket.send(JSON.stringify({id, method, params}));
  });
}

async function evaluate(expression) {
  const result = await command('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}

const snapshot = () => evaluate(`(() => {
  const {layout, mounts, unmounts} = window.layoutBaseline;
  const root = document.querySelector('.ainew-layout');
  return {width: root.getBoundingClientRect().width, mode: layout.mode, mounts, unmounts,
    panels: [...root.querySelectorAll('.ant-splitter-panel')].map(el => el.getBoundingClientRect().width),
    visible: layout.visible, notice: root.querySelector('[role=status]')?.textContent ?? null,
    drafts: [...root.querySelectorAll('input,textarea')].map(el => el.value),
    focusedRegion: document.activeElement?.closest('[data-region]')?.dataset.region ?? null};
})()`);
const samples = [], checks = [], pageErrors = [];
await mkdir(output, {recursive: true});
for (const file of ['layout.json', 'layout.png', 'layout-375.png', 'layout-830.png', 'layout-1180.png', 'failure.json']) {
  await rm(join(output, file), {force: true});
}
try {
  server = await createServer({
    root: directory, configFile: false, esbuild: {jsx: 'automatic'},
    resolve: {alias: {'@': resolve(frontend, 'src')}},
    server: {host: '127.0.0.1', port: 0, fs: {allow: [frontend]}},
    plugins: [{
      name: 'layout-baseline', configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url !== '/') return next();
          res.setHeader('Content-Type', 'text/html; charset=utf-8');
          res.end('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>ChatNew 布局基线验收</title>' +
            '<style>body{margin:0}#root{display:flex;width:1920px;height:720px}input,textarea{max-width:90%}</style>' +
            '<div id="root"></div><script type="module" src="/browser.tsx"></script></html>');
        });
      }
    }]
  });
  await server.listen();
  let stderr = '', launchError;
  chrome = spawn(chromePath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
      `--user-data-dir=${profile}`, '--remote-debugging-port=0', '--window-size=1920,900', 'about:blank'],
    {stdio: ['ignore', 'ignore', 'pipe']});
  chrome.stderr.on('data', chunk => {
    stderr += chunk;
  });
  chrome.on('error', error => {
    launchError = error;
  });
  const port = await wait(async () => {
    if (launchError) throw launchError;
    if (chrome.exitCode !== null) throw new Error(`Chrome exited: ${stderr.slice(-2000)}`);
    try {
      return Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]);
    } catch {
      return null;
    }
  });
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, {method: 'PUT'})).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = reject;
  });
  socket.onmessage = ({data}) => {
    const value = JSON.parse(String(data));
    if (value.method === 'Runtime.exceptionThrown') pageErrors.push(value.params.exceptionDetails);
    const response = pending.get(value.id);
    if (!response) return;
    pending.delete(value.id);
    clearTimeout(response.timer);
    value.error ? response.reject(new Error(JSON.stringify(value.error))) : response.resolve(value.result);
  };
  await command('Page.enable');
  await command('Runtime.enable');
  await command('Page.navigate', {url: server.resolvedUrls.local[0]});
  await wait(() => evaluate('!!window.layoutBaseline && window.layoutBaseline.mounts === 1'));
  const resize = async width => {
    await evaluate(`document.getElementById('root').style.width = '${width}px'`);
    await wait(() => evaluate(`window.layoutBaseline.layout.width === ${width}`));
    await evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    return snapshot();
  };
  for (const width of [1920, 1366, 1180, 1179.75, 1180, 1179, 1080, 830, 829.75, 830, 829, 780, 375, 350, 349]) {
    const sample = await resize(width);
    assert.equal(sample.mode, width >= 1180 ? 'three' : width >= 830 ? 'two' : 'single');
    if (width >= 830) {
      assert.ok(sample.panels[1] >= 479.9, JSON.stringify(sample));
      for (const side of [sample.panels[0], sample.panels[2]].filter(size => size > 0.1)) {
        assert.ok(side >= 349.9 && side <= width / 2 + 0.1, JSON.stringify(sample));
      }
    } else if (width >= 350) {
      assert.ok(Math.abs(sample.panels[1] - width) < 0.1, JSON.stringify(sample));
    }
    assert.ok(Math.abs(sample.panels.reduce((a, b) => a + b, 0) - width) < 0.1, JSON.stringify(sample));
    assert.equal(!!sample.notice, width < 350);
    if (sample.notice) assert.match(sample.notice, /350px/);
    samples.push(sample);
    if ([375, 830, 1180].includes(width)) {
      const screenshot = await command('Page.captureScreenshot', {
        format: 'png',
        clip: {x: 0, y: 0, width, height: 720, scale: 1}
      });
      await writeFile(join(output, `layout-${width}.png`), Buffer.from(screenshot.data, 'base64'));
    }
  }
  await resize(375);
  await evaluate(`document.querySelector('textarea').value = '跨模式保留草稿';
    document.querySelector('[aria-label="展开会话列表"]').click()`);
  await wait(() => evaluate('window.layoutBaseline.layout.visible.list'));
  assert.equal((await snapshot()).focusedRegion, 'list');
  await evaluate(`document.querySelector('[aria-label="列表草稿"]').value = '保留标题';
    document.querySelector('[data-region="list"] button').click();
    document.querySelector('[aria-label="展开设置侧栏"]').click()`);
  await wait(() => evaluate('window.layoutBaseline.layout.visible.settings'));
  assert.equal((await snapshot()).focusedRegion, 'settings');
  await evaluate(`document.querySelector('[aria-label="参数草稿"]').value = '2048'`);
  const two = await resize(830);
  assert.deepEqual(two.visible, {list: false, chat: true, settings: true});
  await evaluate(`document.querySelector('[aria-label="展开会话列表"]').click()`);
  await wait(() => evaluate('window.layoutBaseline.layout.visible.list && !window.layoutBaseline.layout.visible.settings'));
  const wide = await resize(1920);
  assert.deepEqual(wide.drafts, ['保留标题', '跨模式保留草稿', '2048']);
  assert.equal(wide.mounts, 1);
  assert.equal(wide.unmounts, 0);
  checks.push('区域切换与双栏互斥、焦点转移、跨模式保留三份草稿和组件挂载');
  const bar = await evaluate(`(() => {const r = document.querySelector('.ant-splitter-bar-dragger').getBoundingClientRect();
    return {x: r.x + r.width / 2, y: r.y + r.height / 2};})()`);
  await command('Input.dispatchMouseEvent', {type: 'mousePressed', ...bar, button: 'left', clickCount: 1});
  await command('Input.dispatchMouseEvent', {type: 'mouseMoved', x: bar.x + 800, y: bar.y, buttons: 1});
  await command('Input.dispatchMouseEvent', {
    type: 'mouseReleased',
    x: bar.x + 800,
    y: bar.y,
    button: 'left',
    clickCount: 1
  });
  await wait(async () => (await snapshot()).panels[0] > 350);
  const dragged = await snapshot();
  assert.ok(dragged.panels[1] >= 479.9 && dragged.panels[0] <= 960.1, JSON.stringify(dragged));
  samples.push(dragged);
  checks.push('真实鼠标拖动 Splitter 保持聊天下限及侧栏上限');
  assert.deepEqual(pageErrors, []);
  const screenshot = await command('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
  await writeFile(join(output, 'layout.png'), Buffer.from(screenshot.data, 'base64'));
  const sourceHashes = {};
  for (const file of ['src/features/ainew/useChatLayout.ts', 'src/features/ainew/ChatWorkspaceLayout.tsx',
    'tests/layout-baseline/browser.tsx', 'tests/layout-baseline/run.mjs']) {
    sourceHashes[file] = createHash('sha256').update(await readFile(resolve(frontend, file))).digest('hex');
  }
  const report = {
    status: 'PASS', recordedAt: new Date().toISOString(), sourceHashes,
    baseRevision: execFileSync('git', ['rev-parse', 'HEAD'], {cwd: frontend, encoding: 'utf8'}).trim(),
    scope: '隔离真实布局组件，无业务后端、账户或供应商', checks, samples, pageErrors
  };
  await writeFile(join(output, 'layout.json'), `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({status: report.status, output, samples: samples.length, checks}));
} catch (error) {
  await writeFile(join(output, 'failure.json'), JSON.stringify({error: String(error), samples, pageErrors}, null, 2));
  console.error(error);
  process.exitCode = 1;
} finally {
  for (const {timer} of pending.values()) clearTimeout(timer);
  socket?.close();
  chrome?.kill('SIGTERM');
  await server?.close();
  await pause(500);
  await rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 200});
}
