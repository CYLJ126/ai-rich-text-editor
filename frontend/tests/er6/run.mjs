import {mkdir, mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {execFileSync, spawn} from 'node:child_process';
import {acceptanceServer} from './server.mjs';

/** 无额外浏览器依赖的本机验收入口；只启动独立临时 Chrome profile 和 localhost Vite。 */
const output = resolve(process.env.ER6_OUTPUT_DIR ?? join(tmpdir(), 'arte-er6-browser'));
const chromePath = process.env.ER6_CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const profile = await mkdtemp(join(tmpdir(), 'arte-er6-chrome-'));
let chrome, server, socket;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const deadline = Date.now() + 150000;
const wait = async read => {
  while (Date.now() < deadline) {
    const result = await read();
    if (result) return result;
    await pause(50);
  }
  throw new Error('Browser acceptance timed out');
};
const pending = new Map();
let sequence = 0;

function command(method, params = {}) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    pending.set(id, {resolve, reject});
    socket.send(JSON.stringify({id, method, params}));
  });
}

const evaluate = async expression => {
  const value = await command('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
  if (value.exceptionDetails) throw new Error(value.exceptionDetails.exception?.description ?? value.exceptionDetails.text);
  return value.result.value;
};
await mkdir(output, {recursive: true});
try {
  const started = await acceptanceServer();
  server = started.server;
  let stderr = '';
  chrome = spawn(chromePath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${profile}`, '--remote-debugging-port=0', '--window-size=1365,1000', 'about:blank'], {stdio: ['ignore', 'ignore', 'pipe']});
  chrome.stderr.on('data', data => {
    stderr += data.toString();
  });
  chrome.on('error', error => {
    stderr += String(error);
  });
  const port = await wait(async () => {
    if (chrome.exitCode !== null) throw new Error(`Chrome exited ${chrome.exitCode}: ${stderr.slice(-2000)}`);
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
    const response = pending.get(value.id);
    if (!response) return;
    pending.delete(value.id);
    value.error ? response.reject(new Error(JSON.stringify(value.error))) : response.resolve(value.result);
  };
  await command('Page.enable');
  await command('Runtime.enable');
  await command('Page.navigate', {url: started.url});
  await command('Page.bringToFront');
  await wait(() => evaluate("typeof window.er6Run === 'function'"));
  let scenarioTimer;
  const timeout = new Promise((_, reject) => {
    scenarioTimer = setTimeout(() => reject(new Error('Scenario timeout')), 120000);
  });
  let result;
  try {
    result = await Promise.race([evaluate('window.er6Run()'), timeout]);
  } finally {
    clearTimeout(scenarioTimer);
  }
  const percentiles = name => {
    const values = result.samples.map(value => value[name]).sort((a, b) => a - b);
    return {
      p50: values[Math.ceil(values.length * 0.5) - 1],
      p95: values[Math.ceil(values.length * 0.95) - 1],
      max: values.at(-1)
    };
  };
  const report = {
    recordedAt: new Date().toISOString(),
    baseRevision: execFileSync('git', ['rev-parse', 'HEAD'], {encoding: 'utf8'}).trim(),
    viewport: {width: 1365, height: 1000},
    status: 'PASS',
    failures: [], ...result,
    summary: {
      sampleCount: result.samples.length, refreshStartMs: percentiles('refreshStartMs'),
      uiRecoveryMs: percentiles('uiRecoveryMs'), totalMs: percentiles('totalMs')
    }
  };
  await writeFile(join(output, 'browser.json'), JSON.stringify(report, null, 2) + '\n');
  const screenshot = await command('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
  await writeFile(join(output, 'browser.png'), Buffer.from(screenshot.data, 'base64'));
  console.log(JSON.stringify({output, status: report.status, ...report.summary, fallbackMs: report.fallbackMs}));
} catch (error) {
  if (socket?.readyState === WebSocket.OPEN) {
    const diagnostic = await evaluate('({text: document.body.innerText, html: document.body.innerHTML, metrics: window.__er6Metrics})').catch(() => null);
    await writeFile(join(output, 'failure.json'), JSON.stringify({error: String(error), diagnostic}, null, 2));
  }
  console.error(error);
  process.exitCode = 1;
} finally {
  socket?.close();
  chrome?.kill('SIGTERM');
  await server?.close();
  await pause(500);
  await rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 200});
}
