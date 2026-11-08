# ER-6 会话异常恢复验收记录

2026-11-08。 **技术验证已执行；五人用户试用未执行，ER-6 尚未全部验收通过。**
用户确认目前没有试用记录或可安排的五名参与者，因此保留原试用门槛，交付[任务脚本和空白记录](ER6_USER_TRIAL.md)
，不以自动化或开发者自测替代普通用户结果。

## 环境与结果

- 后端：JDK 21，新建 H2 MySQL 模式数据库、真实应用／事务组件、仅监听 `127.0.0.1` 的模拟供应商 HTTP/SSE。公共账户授权使用实际宿主适配器、公共
  ACL／成员／RBAC。跨实例通知另使用本机已有 `redis:7.4-alpine` 镜像启动独立无挂载、无持久化、随机本机端口的 Redis
  容器，复测完成已停止删除。
- 浏览器：1365×1000 的独立临时 Chrome profile、headless Chrome、`visibilityState=visible`、真实时钟和计时器；真实 React
  业务组件、服务校验／取消和 SSE 解析，对接隔离 HTTP/SSE 事实模拟器。它不是完整应用到真实后端的端到端部署，不证明真实数据库延迟或生产容量。
- 没有真实模型请求、真实账单、业务数据库授权、生产部署或外部通知。真实费用核对依然要求可归属账单及执行结束证据；没有证据继续挂起资金。

| 检查            | 本轮最终结果                                                                                           | 留档                                                                                |
|-----------------|--------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 后端相关 21 组  | 433 项通过，0 失败／错误／跳过；含新增自动租约收尾计时                                                 | [全部组与具名用例](acceptance/er6/backend.json)                                     |
| 前端相关回归    | 40 个文件、467 项全部通过，0 跳过；既有布局基线单独记录                                                | [逐文件／用例结果](acceptance/er6/frontend.json)                                    |
| Chrome 异常恢复 | 30／30 通过，三类终态事实各 10 次；另测十秒兜底、复制、键盘及费用焦点                                  | [全部原始样本](acceptance/er6/browser.json)、[组件截图](acceptance/er6/browser.png) |
| Worker 自动收尾 | 实际等待两份 3 秒租约到期，扫描 100ms；到期后发现 5.062ms、处理 22.196ms，合计 27.258ms；未重发模型    | [计时原始数据](acceptance/er6/worker.json)                                          |
| TypeScript      | 107 项既有诊断，与 ER-5 按文件及错误内容规范化对比无新增／消除；全项目类型检查未通过                   | `npm run tsc`                                                                       |
| 既有布局基线    | 未改动的 `ChatWorkspaceLayout.test.tsx`：5 通过、3 失败；仍为实现 SIDE_MIN=350 与测试预期 300px 的差异 | 独立复测，不计入相关回归通过数                                                      |
| 五人任务试用    | 未执行、无结果；不能推断 4／5 任务成功或关键事实全部理解                                               | [试用材料](ER6_USER_TRIAL.md)                                                       |

后端首次在沙箱内运行时，36 项模拟 HTTP/SSE 测试因禁止绑定本机端口失败；允许本机绑定后同组全部通过。随后补跑新 Worker 用例及原先需
Redis 的三项测试，最终对应组均无跳过；433 为按具名用例去重后的总数，不累加重复运行次数。期间新增计时用例的 Instant
导入缺失已修正并复测。

前端首次相关扩展回归出现一项搜索导航测试超过 15 秒；该用例单独复测通过，随后完整 40 文件／467
项复测全部通过。最终相关回归结果及该次超时保留，不通过删除用例、延长测试阈值或修改生产搜索逻辑掩盖。

## 恢复时延

浏览器为每个新建隔离会话采集：匹配终态 SSE 字节收到、第一项权威读取开始、恢复前必要读取最后完成、DOM 中发送按钮启用四个时点。DOM
条件观察间隔 8ms，因此启用时间是观测上界；下一帧并等待 250ms 后确认仍可发送。部分断流保留已有文字并明确整轮未纳入上下文；每次恢复的服务端写请求增量为
0。

| 指标（毫秒）              |  P50 |   P95 |  最大 | 对应目标                     |
|---------------------------|-----:|------:|------:|------------------------------|
| 收到终态至启动权威读取    | 57.5 |  58.2 |  58.5 | ≤1000，30 次均满足           |
| 必要读取完成至 DOM 可发送 |  7.2 |   9.2 |   9.7 | ≤500，30 次均满足            |
| 收到终态至 DOM 可发送     | 96.1 | 108.0 | 108.1 | 端到端观测值，设计未单列阈值 |

没有终态 SSE 时另测真实十秒单飞兜底：从模拟权威终态提交到可发送为 **10040.5ms**，随后再等待 500ms 确认稳定可用。该项不是 30
次 SSE 样本之一。自动恢复产生模型／结算／指派写请求均为 0；复制和查看费用也不产生写请求。

Worker 使用真实 3 秒 Attempt 租约和 3 秒 Outbox 租约，按两者较晚到期时间计时，不改写数据库过期字段。自动扫描周期 100ms，发现耗时
5.062ms，整个协调处理 22.196ms（含 H2 状态／账本事务，不能称为单一 SQL 事务耗时）；合计 27.258ms。旧 worker fence 拒绝，UNKNOWN
释放原会话占用，原预留仍 PENDING_RECONCILIATION，恢复没有新增供应商请求。此为本机单次 Worker 验证，不冒充三十次分位数或生产测量。

## ER-AC01～ER-AC38 证据映射

下表的“通过”只表示本轮列出的隔离自动化技术证据，不代替五人无解释任务成功率或完整部署联调。源文件别名：

- D：[DispatchIntegrationTest](src/test/java/com/arte/ainew/admission/DispatchIntegrationTest.java)，真实本机供应商协议与账本闭环。
- P：[MybatisExecutionPersistenceTest](src/test/java/com/arte/ainew/persistence/MybatisExecutionPersistenceTest.java)
  ，真实事务、双实例及并发。
-
R：[RegenerationReconciliationIntegrationTest](src/test/java/com/arte/ainew/admission/RegenerationReconciliationIntegrationTest.java)
，历史、快照、重新生成和核对。
-
A：[BudgetAccountAccessIntegrationTest](../arte-app/src/test/java/com/arte/app/platform/persistence/BudgetAccountAccessIntegrationTest.java)
，实际公共账户授权宿主；另含 ResourceAcl／DatabaseAiControl 集成组。
-
F：[recovery.test.tsx](../../frontend/src/features/ainew/recovery.test.tsx)、[chatSession.test.tsx](../../frontend/src/pages/AI/Chat/chatSession.test.tsx)、[管理核对组件测试](../../frontend/src/features/ainew/BudgetReconciliationManager.test.tsx)。
- B：[Chrome 验收入口](../../frontend/tests/er6/browser.tsx)，原始测量见上文。

| 编号 | 本轮技术证据                                                                                                                                                                                | 结果与适用范围                                                                          |
|------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| AC01 | D.uncertainServerFailureNeverRetriesEvenWithAttemptsAvailable、ordinaryUnknownCanSubmitAnIndependentQuestionWithoutResendingTheOriginal；B                                                  | 通过；未知结果不重发，释放本地占用且保留资金                                            |
| AC02 | D.interruptedProviderKeepsPartialResultUnknownOutcomeAndHeldBudget、partialOutputFailureNeverRetriesWithAttemptsAvailable；B 部分文字                                                       | 通过；部分可见、未纳入上下文、不自动重派                                                |
| AC03 | D.er6AutomaticLeaseRecoveryIsMeasuredWithoutAnotherProviderRequest；P.expiredOrdinaryWorkerReleasesGateAndRollsBackFenceWhenGateWriteFails                                                  | 通过；真实租约收尾与旧 fence 拒绝                                                       |
| AC04 | P.cancellationAndMarkDispatchCompeteUnderTheSameDatabaseLock；F 停止回执仍等待权威状态                                                                                                      | 通过；不以停止受理回执伪造终态                                                          |
| AC05 | ChatHttp.concurrentDuplicateHttpSubmissionsCreateOnlyOneInvocation、replayReturnsOriginalReceiptAfterVersionAdvanceAndRejectsChangedSemantics；F 原键恢复                                   | 通过；不确定提交使用原请求／原键                                                        |
| AC06 | R.ordinaryUnknownCanRegenerateWithIndependentFundsAndOldBillCannotClearNewGate；D.regenerationStreamsAsAnIndependentCallAndBillsBothReplies                                                 | 通过；独立调用和预留                                                                    |
| AC07 | R.repeatedUnknownRegenerationUsesOneAcceptanceAcrossInstances；F 固定重新生成请求                                                                                                           | 通过；原回执、候选与费用防重                                                            |
| AC08 | R.regenerationUsesExactModelAndReceiptDoesNotRequireReenabledModel、snapshotMismatchDoesNotRebuildARegenerationFromDifferentHistory；F                                                      | 通过；原精确模型／快照，拒绝不合法新操作                                                |
| AC09 | B 复制无写；F 当前配置发送；R 快照固定                                                                                                                                                      | 通过；复制只准备草稿，新问题走当前配置                                                  |
| AC10 | B 默认保留／替换及焦点；F 同场景                                                                                                                                                            | 通过；已有草稿不自动覆盖                                                                |
| AC11 | P.unknownRegenerationDoesNotChangeInputModelBudgetOrAllowOldTurns；F 最新轮次动作                                                                                                           | 通过；不回写旧轮                                                                        |
| AC12 | R.everyKnownFailureSkipsBothQuestionAndAnswer、failedRegenerationWithoutSuccessfulCandidateSkipsTheEntireTurn；B                                                                            | 通过；失败整轮排除                                                                      |
| AC13 | R.failedRegenerationKeepsPriorFullAnswerAndBillDoesNotChangeItsContext；F 当前查看候选标记                                                                                                  | 通过；旧完整有效候选仍被采用                                                            |
| AC14 | R.ordinaryUnknownCanContinueBeforeBillAndOldBillCannotChangeAcceptedInput；F 旧核对刷新                                                                                                     | 通过；不改变已保存输入和新候选                                                          |
| AC15 | P.ordinaryUnknownAllowsOnlyOneNewEntryAcrossInstances；ChatHttp 并发同键                                                                                                                    | 通过；双实例 DB 竞争；浏览器两个真实标签同时提交未单独联调                              |
| AC16 | R.stoppedPartialCostClosesAtomicallyWithoutReleasingANewerConversationGate；P.releaseIsConditionalOnBothInvocationIdAndExecutionKind                                                        | 通过；核对和后续占用隔离，沿既有锁序                                                    |
| AC17 | P.ordinaryUnknownReleasesOwnGateAndKeepsReservationAndOldWorkerFenced；F 迟到文字／旧响应隔离                                                                                               | 通过；旧 fence 与页面迟到事件分别覆盖                                                   |
| AC18 | P.completionAndGateFailureRollBackTerminalAttemptEventsAndReceipt、acceptanceRollsBackInvocationTurnAndOutboxTogether                                                                       | 通过；相关事务全部回滚                                                                  |
| AC19 | P.competingReservationsCannotOverspendTheSharedBudget；R 独立预留；F 预留占用提示                                                                                                           | 通过；原子预算检查和资金占用说明                                                        |
| AC20 | R.privilegeOwnershipVersionsCurrencyAndActiveStateAreCheckedBeforeWriting、remoteReadOnlyGrantCanListButCannotConfirmAndRegenerationRequiresAKey；A                                         | 通过；本人／管理读写分开授权                                                            |
| AC21 | R.httpVerifiesAmountsEvidenceAuthorizationAndDurableReceipt、finalChargeCannotBeOverwrittenOrAppliedTwiceAcrossInstances、tinyBillAmountIsReturnedAsPlainDecimalString                      | 通过；明确零、高精度、超额及不可覆盖结算                                                |
| AC22 | P.dispatchedCancellationDoesNotFabricateRemoteOutcomeAndSurvivesReconstruction；F 卸载重挂／刷新恢复                                                                                        | 通过；适配器重建与浏览器组件恢复；未执行完整服务进程重启联调                            |
| AC23 | ConversationHttp.corruptActiveReferenceDoesNotBecomeAnIdleResponse；R.noOutputUnknownCanContinueWhileReservationIsStillReserved；F                                                          | 通过；缺失事实拒绝，费用观察不冒充生成                                                  |
| AC24 | A 当前成员／授权撤销；F 账号／空间／会话变更、迟到响应及无权内容清理                                                                                                                        | 通过；浏览器组件和宿主权限分别覆盖                                                      |
| AC25 | P.otherUnknownInputsKeepGateAndCannotBecomeRegenerationSourcesEvenIfGateIsEmpty；R.toolBearingStructuredAndChildUnknownsStillRejectHistoryAssembly；工具相关 5 组                           | 通过；工具／Run／编排子调用保持原限制                                                   |
| AC26 | R.missingInvocationWrongLinkAndCorruptSuccessAreNotSkippedAsOrdinaryFailures、incompleteSuccessReturnedByResultPortIsAnErrorInsteadOfAnEmptyHistory                                         | 通过；损坏成功结果不被吞掉                                                              |
| AC27 | R.regenerationReceiptSurvivesDeletedSourceSnapshotAndStillRejectsChangedOperation、regenerationUsesExactModelAndReceiptDoesNotRequireReenabledModel；F 复制出口                             | 通过；新操作拒绝失效来源，原回执独立                                                    |
| AC28 | B 复制默认焦点、输入法／Shift+Enter、费用关闭焦点；F 动作与禁用提示                                                                                                                         | 技术通过；真实用户的文案理解、键盘任务体验待试用                                        |
| AC29 | A 具名责任／离任派生；R.managedFinancialReadAndConfirmationRetainOwnerAndActualActor；F 管理核对                                                                                            | 技术通过；三身份完整部署及普通用户实际可达性仍待试用                                    |
| AC30 | A.permitRechecksGrantAndIndependentOperationInLedgerTransaction；R.sameTargetAndKeyReplayAcrossSelfAndManagedEntriesNeverChargesAgain、managedAuditFailureRollsBackFeesInvocationAndReceipt | 通过；授权、真实 actor／owner、原键和审计事务                                           |
| AC31 | A.responsibilityBecomesUnassignedWhenThePrimaryLeaves；F 无证据／无责任人；B 费用提示                                                                                                       | 技术通过；证据不可归属仍挂起，普通用户协作路径待试用                                    |
| AC32 | F.recovers by a known ID with an invalid request/key、requires authorized conversation ownership                                                                                            | 通过；当前授权／归属及保留损坏证据                                                      |
| AC33 | F.restores an uncertain message across unmount and keeps the original config and key                                                                                                        | 通过；用户显式确认，无自动写入                                                          |
| AC34 | F.isolates an unrecoverable record locally、lets the user isolate corrupt recovery through the page                                                                                         | 通过；只隔离本地导航限制，不解锁原会话写入                                              |
| AC35 | F.an old nonblocking UNKNOWN cannot override a current INVOCATION／RUN occupant                                                                                                             | 通过；权威会话占用优先                                                                  |
| AC36 | P.executionStateUsesOneSnapshotWhileAnotherInstanceAdvancesConversation；useConversationExecutionState 同版本／逆序组                                                                       | 通过；一致性、同版本释放与响应顺序                                                      |
| AC37 | B 30 次原始时点与十秒兜底；D 实际租约测量；recoveryRead.test.ts 的超时／退避／隐藏／永久错误                                                                                                | 本机隔离口径通过；生产环境未测                                                          |
| AC38 | 无真实用户记录                                                                                                                                                                              | **未执行**；至少五名未参与实现者，任务 ≥4／5 独立完成，两条关键事实全员理解；见试用脚本 |

## 复测方式

前端相关回归（在 `frontend`）：

```bash
npm test -- src/services/arte-ai src/services/platform/budgetAccount.test.ts src/features/ainew src/pages/AI/ChatNew src/pages/AI/Chat/chatSession.test.tsx src/pages/AI/Chat/reconciliation.test.tsx src/pages/AI/Chat/configurationSelection.test.tsx --exclude src/features/ainew/ChatWorkspaceLayout.test.tsx --maxWorkers=2 --reporter=dot
node tests/er6/run.mjs
npm run tsc
```

后端使用 JDK 21 和 Mockito Java agent，从根目录运行；`MOCKITO_AGENT_JAR` 为本机 Mockito core agent 的真实绝对路径：

```bash
mvn -o -f backend/pom.xml -pl arte-app -am \
  "-DargLine=-javaagent:$MOCKITO_AGENT_JAR" \
  -Dtest=MybatisExecutionPersistenceTest,RegenerationReconciliationIntegrationTest,AdmissionIntegrationTest,ChatHttpIntegrationTest,ConversationExecutionPolicyTest,ConversationHttpIntegrationTest,InvocationHttpIntegrationTest,DispatchIntegrationTest,DispatchWorkerIntegrationTest,DispatchPreparationIntegrationTest,FreeDispatchPreparationIntegrationTest,ToolChatHistoryStoreTest,ToolChatLoopTest,ToolChatBudgetStoreTest,ToolInvocationIntegrationTest,ToolSubmissionExecutionTest,ToolSchedulingIntegrationTest,BudgetHttpIntegrationTest,BudgetAccountAccessIntegrationTest,ResourceAclIntegrationTest,DatabaseAiControlIntegrationTest \
  -Darte.ai-new.test.redis-address=redis://127.0.0.1:ISOLATED_TEST_PORT \
  -Darte.ai-new.test.er6-report=/private/tmp/er6-worker.json \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

需要允许监听本机端口和一个全新、无业务数据的 Redis 实例。省略 Redis 属性会明确跳过三项跨实例通知测试，不算该部分已通过。切勿指向已有业务
Redis。

## 仍待完成

五名普通用户任务试用及其误解复测（含三身份协作核对）仍待完成。并发与重启恢复目前分别由同库双实例、适配器重建及组件重挂测试提供技术证据，没有执行两个真实浏览器标签同时操作或完整服务进程重启联调；这些范围不能从分层测试结果外推。生产数据库／真实账单验证和上线也不属于本轮已执行事实。ER-6
的用户试用退出条件尚未满足，不能据此将整体异常恢复标记为正式交付。
