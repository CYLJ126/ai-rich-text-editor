import {createServer} from 'vite';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url)),
  frontend = path.resolve(root, '../..');
const states = new Map(),
  calls = [],
  keys = new Map();
const now = '2026-10-20T12:00:00Z';
const base = {
  runId: 'run',
  version: 0,
  state: 'ACCEPTED',
  checkpoint: {roundIndex: 0, phase: 'READY_MODEL', children: []},
  modelSteps: 0,
  toolSteps: 0,
  resultAvailable: false,
  cancellationRequested: false,
  error: null,
  acceptedAt: now,
  updatedAt: now,
};
const state = (scope) => {
  const id = scope.workspaceId;
  if (!states.has(id))
    states.set(id, {
      accepted: false,
      status: {...base},
      events: [],
      text: '统计这段文字',
    });
  return states.get(id);
};
const message = (id, role, text) => ({
  messageId: id,
  role,
  content: [{text}],
  toolCalls: [],
  toolCallId: null,
});
const answer = {
  runId: 'run',
  finalInvocationId: 'final-child',
  result: {
    value: {
      resultId: 'result',
      model: {providerId: 'fixture', modelId: 'model', revision: null},
      outputs: [
        message(
          'answer-message',
          'ASSISTANT',
          '这段文字共 **7** 个字符。\n\n工具执行已经完成。',
        ),
      ],
      finishReason: 'STOP',
      complete: true,
      structuredOutput: null,
      usage: {
        basis: 'PROVIDER_REPORTED',
        inputTokens: 100,
        outputTokens: 10,
        totalTokens: 110,
      },
      sources: [],
    },
  },
};
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
    port: 5246,
    strictPort: true,
    fs: {allow: [frontend]},
  },
  plugins: [
    {
      name: 'isolated-tool-chat-fixture',
      configureServer(vite) {
        vite.middlewares.use(async (req, res, next) => {
          if (req.url === '/fixture/calls') {
            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify(calls));
            return;
          }
          if (!req.url.startsWith('/arte/') && !req.url.startsWith('/fixture/'))
            return next();
          let raw = '';
          for await (const chunk of req) raw += chunk;
          const body = raw ? JSON.parse(raw) : {},
            current = state(body.scope ?? {workspaceId: 'workspace'});
          calls.push({
            path: req.url,
            key: req.headers['idempotency-key'],
            body,
          });
          if (req.url.startsWith('/fixture/')) {
            const name = req.url.split('/').at(-1),
              value = state({workspaceId: 'workspace'});
            if (name === 'reset') {
              states.clear();
              keys.clear();
            } else {
              const statesByName = {
                approval: [
                  'WAITING_APPROVAL',
                  'WAITING_APPROVAL',
                  ['tool-child'],
                ],
                unknown: [
                  'PENDING_RECONCILIATION',
                  'RECONCILING',
                  ['tool-child'],
                ],
                success: ['SUCCEEDED', 'FINISHED', []],
                running: ['RUNNING', 'WAITING_MODEL', ['model-child']],
              };
              const [status, phase, children] = statesByName[name];
              value.status = {
                ...value.status,
                version: value.status.version + 1,
                state: status,
                checkpoint: {roundIndex: 1, phase, children},
                modelSteps: 2,
                toolSteps: 1,
                resultAvailable: name === 'success',
              };
              value.events.push({
                runId: 'run',
                sequence: value.status.version + 1,
                state: status,
                checkpoint: value.status.checkpoint,
                occurredAt: now,
              });
            }
            res.end('{}');
            return;
          }
          const endpoint = req.url.split('/').at(-1);
          let data;
          if (endpoint === 'getWorkspacePreference')
            data = {
              defaultScope: {tenantId: 'tenant', workspaceId: 'workspace'},
              startupScope: null, lastScope: null, favorites: [], version: 0,
            };
          else if (endpoint === 'browseAccessibleWorkspaces')
            data = {
              records: Array.from({length: 12}, (_, i) => ({
                scope: {tenantId: 'tenant', workspaceId: i ? `workspace-${i + 1}` : 'workspace'},
                name: `验收工作空间 ${i + 1}`, description: '用于验证侧栏竖向滚动',
                state: 0, currentRole: 0, version: 0, defaultWorkspace: i === 0,
              })),
              nextCursor: null, exhausted: true,
            };
          else if (endpoint === 'getWorkspace')
            data = {
              scope: body.scope,
              name: '验收工作空间 1',
              description: '用于验证侧栏竖向滚动',
              state: 0,
              currentRole: 0,
              version: 0
            };
          else if (endpoint === 'checkWorkspaceAccess')
            data = {allowed: false, reason: 'FIXTURE_READ_ONLY', checkedAt: now};
          else if (endpoint === 'getConversation')
            data = {
              conversationId: 'conversation',
              title: '工具聊天验收',
              version: current.accepted ? 1 : 0,
              chatProfile: null,
              resources: [],
              state: 'ACTIVE',
              createdAt: now,
              updatedAt: now,
            };
          else if (endpoint === 'queryTurnsOfConversation') {
            res.setHeader('Content-Type', 'application/json');
            res.end(
              JSON.stringify({
                success: true,
                code: '200',
                desc: 'OK',
                records: current.accepted
                  ? [
                    {
                      turnId: 'turn',
                      conversationId: 'conversation',
                      sequence: 1,
                      parentTurnId: null,
                      supersedesTurnId: null,
                      userMessage: message('question', 'USER', current.text),
                      invocationIds: [],
                      selectedInvocationId: current.status.resultAvailable
                        ? 'final-child'
                        : null,
                      candidates: [
                        {kind: 'TOOL_CHAT_RUN', executionId: 'run'},
                      ],
                      selectedCandidate: current.status.resultAvailable
                        ? {kind: 'TOOL_CHAT_RUN', executionId: 'run'}
                        : null,
                      version: 1,
                      createdAt: now,
                      updatedAt: now,
                    },
                  ]
                  : [],
                current: 1,
                size: 10,
                total: current.accepted ? 1 : 0,
              }),
            );
            return;
          } else if (endpoint === 'getBudget')
            data = {
              budgetRef: 'wallet',
              currency: 'CNY',
              limit: '100',
              held: '0.2',
              charged: '0.1',
              available: '99.7',
              rateVersion: {type: 'rate', id: 'rate', version: 'v1'},
              version: 1,
            };
          else if (endpoint === 'discoverToolChat') data = {enabled: true};
          else if (endpoint === 'availableToolBudgets') data = {price: null, accounts: []};
          else if (endpoint === 'listBindings')
            data = {
              records: [
                {
                  bindingId: 'count',
                  toolId: 'count',
                  version: '1',
                  name: '统计字符数',
                  enabled: true,
                  createdAt: now,
                  updatedAt: now,
                },
              ],
              nextCursor: null,
              exhausted: true,
              scanned: 1,
            };
          else if (
            endpoint === 'submitToolChat' ||
            endpoint === 'submitReadOnlyToolChat'
          ) {
            const key = req.headers['idempotency-key'];
            if (keys.has(key) && keys.get(key) !== raw) {
              res.statusCode = 409;
              data = null;
            } else {
              keys.set(key, raw);
              current.accepted = true;
              current.text = body.text;
              current.events = [
                {
                  runId: 'run',
                  sequence: 1,
                  state: current.status.state,
                  checkpoint: current.status.checkpoint,
                  occurredAt: now,
                },
              ];
              res.statusCode = 202;
              data = {executionId: 'run', kind: 'RUN', acceptedAt: now};
            }
          } else if (endpoint === 'getRunStatus') data = current.status;
          else if (endpoint === 'getRunBudget')
            data = [
              {
                runId: 'run',
                currency: 'CNY',
                limit: '1.00',
                charged: '0.100000000000000001',
                held: '0.2',
                available: '0.699999999999999999',
                blocked: false,
                version: 1,
              },
            ];
          else if (endpoint === 'replayRunEvents')
            data = current.events.filter(
              (e) => e.sequence > body.afterSequence,
            );
          else if (endpoint === 'getRunResult') data = answer;
          else if (endpoint === 'getRunActions')
            data = {
              runId: 'run',
              text: current.text,
              answeredAt: now,
              readOnlyBindingIds: ['count'],
              writeBindingIds: [],
            };
          else if (endpoint === 'cancelRun') {
            current.status.cancellationRequested = true;
            res.statusCode = 202;
            data = current.status;
          } else if (endpoint === 'getInvocationSnapshots')
            data = body.invocationIds.map((invocationId) => ({
              invocationId,
              missing: true,
              status: null,
              result: null,
            }));
          else if (endpoint === 'watchInvocation') {
            res.setHeader('Content-Type', 'text/event-stream');
            res.write(
              `event: text-delta\ndata: ${JSON.stringify({
                executionId: body.invocationId,
                attemptId: 'attempt',
                offset: 0,
                text: '正在分析文字…'
              })}\n\n`,
            );
            req.on('close', () => res.end());
            return;
          } else if (endpoint === 'getApproval')
            data = {
              approvalId: 'approval',
              invocationId: 'tool-child',
              version: '1',
              subjectDigest: 'a'.repeat(64),
              mode: 'REQUESTER_CONFIRMATION',
              state: 'PENDING',
              requesterSubjectId: 'user',
              approverSubjectIds: [],
              title: '测试固定工具',
              toolVersion: 'v1',
              bindingVersion: '1',
              argumentsJson: '{"text":"测试"}',
              configurationJson: '{}',
              sideEffect: 'READ_ONLY',
              expiresAt: new Date(Date.now() + 60000).toISOString(),
              executeNotAfter: new Date(Date.now() + 120000).toISOString(),
              decidedBy: null,
              decidedAt: null,
              invocationState: 'WAITING_APPROVAL',
              mayDecide: true,
              requester: true,
              serverTime: new Date().toISOString(),
            };
          else {
            res.statusCode = 404;
            data = null;
          }
          res.setHeader('Content-Type', 'application/json');
          res.end(
            JSON.stringify({
              success: res.statusCode < 400,
              code: '200',
              desc: 'OK',
              data,
            }),
          );
        });
      },
    },
  ],
});
await server.listen();
server.printUrls();
