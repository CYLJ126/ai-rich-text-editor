import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createServer} from 'vite';

const directory = fileURLToPath(new URL('.', import.meta.url));
const frontend = resolve(directory, '../..');
const now = () => new Date().toISOString();

/** 本机故障事实模拟器；账本／授权真实事务另由 Java 集成测试验证。 */
export async function acceptanceServer() {
  let sample;
  const streams = new Set();
  const counters = {writes: 0, forbiddenReads: 0};
  const envelope = data => ({success: true, code: '200', desc: 'OK', data});
  const conversation = () => ({
    conversationId: sample.id, title: '隔离恢复验收', version: 1,
    state: 'ACTIVE', chatProfile: null, resources: [], createdAt: sample.at, updatedAt: sample.at
  });
  const status = () => ({
    invocationId: sample.invocation,
    kind: 'GENERATION',
    conversation: {conversationId: sample.id, conversationVersion: 1, turnId: `turn-${sample.id}`},
    blocksConversation: !sample.terminal,
    state: sample.terminal ? 'UNKNOWN' : 'RUNNING',
    version: sample.terminal ? 4 : 2,
    budgetState: sample.terminal ? 'PENDING_RECONCILIATION' : 'RESERVED',
    activeAttemptId: 'attempt',
    resultAvailable: false,
    partial: null,
    error: sample.terminal ? {
      code: sample.mode,
      phase: 'INVOCATION',
      retryable: false,
      sideEffect: 'POSSIBLE',
      certainty: 'UNKNOWN',
      traceId: 'isolated'
    } : null,
    acceptedAt: sample.at,
    updatedAt: sample.at
  });
  const server = await createServer({
    root: directory, configFile: false, server: {host: '127.0.0.1', port: 0, fs: {allow: [frontend]}},
    resolve: {alias: {'@umijs/max': resolve(directory, 'umi.ts'), '@': resolve(frontend, 'src')}},
    esbuild: {jsx: 'automatic'}, plugins: [{
      name: 'er6-isolated-api', configureServer(vite) {
        vite.middlewares.use(async (req, res, next) => {
          if (!req.url.startsWith('/__er6/') && !req.url.startsWith('/arte/')) return next();
          try {
            const chunks = [];
            for await (const chunk of req) chunks.push(chunk);
            const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
            const send = (data, code = 200) => {
              res.statusCode = code;
              res.setHeader('Content-Type', 'application/json');
              res.end(JSON.stringify(data));
            };
            if (req.url === '/__er6/reset') {
              for (const stream of streams) stream.end();
              streams.clear();
              sample = {
                id: body.id,
                invocation: `inv-${body.id}`,
                at: now(),
                terminal: false,
                mode: body.mode,
                offline: !!body.offline
              };
              return send(envelope(conversation()));
            }
            if (req.url === '/__er6/terminal') {
              sample.terminal = true;
              for (const stream of streams) stream.write(`id: 2\nevent: invocation\ndata: ${JSON.stringify({
                executionId: sample.invocation,
                sequence: 2,
                kind: 'TERMINAL'
              })}\n\n`);
              return send(envelope({sent: streams.size}));
            }
            if (req.url === '/__er6/counters') return send(envelope({...counters, streams: streams.size}));
            if (req.url.endsWith('/watchInvocation')) {
              if (sample.offline) return send({success: false, code: '503', desc: 'isolated SSE unavailable'}, 503);
              res.setHeader('Content-Type', 'text/event-stream');
              res.setHeader('Cache-Control', 'no-cache');
              res.write(': connected\n\n');
              streams.add(res);
              res.on('close', () => streams.delete(res));
              if (sample.mode === 'CONNECTION_LOST') res.write(`event: text-delta\ndata: ${JSON.stringify({
                executionId: sample.invocation,
                attemptId: 'attempt',
                offset: 0,
                text: '这是一段未完成的回复'
              })}\n\n`);
              return;
            }
            if (req.url.endsWith('/getBudget')) return send(envelope({
              budgetRef: 'budget',
              currency: 'CNY',
              limit: '100',
              held: '1',
              charged: '0',
              available: '99',
              version: 1,
              rateVersion: {type: 'rate', id: 'rate', version: 'v1'},
              accountRef: {tenantId: 'tenant', type: 'budget-account', resourceId: 'budget'},
              reconciliationHandling: {status: 'UNASSIGNED', responsible: null}
            }));
            if (req.url.endsWith('/getConversation')) return send(envelope(conversation()));
            if (req.url.endsWith('/getConversationExecutionState')) return send(envelope({
              conversationId: sample.id,
              version: 1,
              activeExecution: sample.terminal ? null : {kind: 'INVOCATION', executionId: sample.invocation},
              observedAt: now()
            }));
            if (req.url.endsWith('/queryTurnsOfConversation')) {
              const turn = {
                turnId: `turn-${sample.id}`,
                conversationId: sample.id,
                sequence: 1,
                version: 1,
                parentTurnId: null,
                supersedesTurnId: null,
                selectedInvocationId: sample.invocation,
                invocationIds: [sample.invocation],
                userMessage: {
                  messageId: 'question', role: 'USER',
                  content: [{text: '隔离验收的原问题'}], toolCalls: [], toolCallId: null
                },
                createdAt: sample.at,
                updatedAt: sample.at
              };
              return send({
                success: true,
                code: '200',
                desc: 'OK',
                records: [turn],
                current: body.page.current,
                size: body.page.size,
                total: 1
              });
            }
            if (req.url.endsWith('/getInvocationSnapshots')) return send(envelope(body.invocationIds.map(invocationId => ({
              invocationId,
              status: status(),
              result: null,
              missing: false
            }))));
            if (req.url.endsWith('/getInvocationStatus')) return send(envelope(status()));
            if (req.url.endsWith('/pendingReconciliations')) return send(envelope({
              page: {
                records: [],
                total: 0,
                current: 1,
                size: 10
              }, canReconcile: false
            }));
            if (/turnsForChat|regenerate|cancelInvocation|confirm.*Reconciliation|grantResourceAccess|revokeResourceGrant/.test(req.url)) counters.writes++;
            else counters.forbiddenReads++;
            return send({success: false, code: '403', desc: 'Not part of this isolated scenario'}, 403);
          } catch (error) {
            res.statusCode = 500;
            res.end(JSON.stringify({error: String(error)}));
          }
        });
      }
    }]
  });
  await server.listen();
  return {server, url: `http://127.0.0.1:${server.httpServer.address().port}`};
}
