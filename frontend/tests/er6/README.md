# ER-6 隔离 Chrome 验收

从 `frontend` 运行 `node tests/er6/run.mjs`。使用已安装的 Chrome、Vite 和现有前端依赖，不下载浏览器。默认 Chrome 位于 macOS
`/Applications/Google Chrome.app`；其他路径可用 `ER6_CHROME` 指定。结果默认写入系统临时目录 `arte-er6-browser/`，也可设置
`ER6_OUTPUT_DIR`。

入口创建仅绑定 `127.0.0.1` 的临时 Vite 服务和独立 Chrome profile，结束时关闭浏览器、服务并删除
profile。它不读取日常浏览器资料、业务数据库或真实供应商凭据，不加载应用的开发代理。模拟器明确拒绝场景外请求并统计意外读／写。

验收使用真实 `BusinessChatPanel`、`useChatSession`、服务校验、取消、批量读取及 SSE 解析；Umi request 在入口内替换为同源
fetch。HTTP/SSE 服务提供新建隔离会话的已提交运行／UNKNOWN 事实，不运行生产服务器。HTTP 500、部分断流和超时的真实供应商适配器／账本事务由
Java `DispatchIntegrationTest` 另行验证，不能把此浏览器模拟器称为完整后端到浏览器端到端部署。

30 次前台页面恢复按三类终态事实各 10 次执行，采用真实 `performance.now()` 与计时器。采集包含匹配 TERMINAL 的 SSE
字节到达、第一项权威读取开始、恢复前必要读取最后完成、DOM 中发送按钮启用四个时点。DOM 用 8ms 条件观察，因此界面可用时间是观察上界；之后再检查
250ms 内持续可用。报告 P50／P95／最大值、全部原始样本、零自动写请求及失败原因；SSE 不可用另测真实十秒兜底。

同时检查部分输出保留、整轮未进入上下文提示、复制默认保留草稿、明确替换后聚焦输入框、Shift+Enter／输入法组合期间不发送、费用抽屉真实责任／证据限制及关闭后焦点恢复。
`browser.png` 是组件验收画面，未装载整站布局，不能用来评判 ChatNew 整体视觉布局。

产物：`browser.json` 与 `browser.png`，失败时另写 `failure.json`
。本轮留档见 [ER-6 验收记录](../../../backend/arte-ai/ER6_ACCEPTANCE.md)
。真实用户试用另按[试用脚本](../../../backend/arte-ai/ER6_USER_TRIAL.md)组织；自动浏览器和开发者均不计入五名未参与实现的用户。
