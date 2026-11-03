# 模型配置 M5 浏览器隔离夹具

此目录由 A2 夹具升级，目录名保留以便追溯。`main.tsx` 只由本目录的 `index.html` 加载，不是正式应用入口；生产入口和路由仍由
Umi 管理。

夹具挂载正式模型页、工作空间页、本人使用准备与聊天参数组件，请求真实 MVC 控制器和全新临时 H2。聊天发送按钮调用测试桥，由正式
ChatService、GenerationDispatcher、HTTP 模型网关和持久化账本执行。本目录没有复制模型配置业务，也不挂载完整 ChatNew 页面。

`shim.ts` 仅替换 Umi 路由、固定测试会话及请求适配，并提供响应丢失开关；它不模拟业务接口。`server.mjs` 提供 Vite 服务，只监听
`127.0.0.1:5234`，把 `/arte` 和 `/fixture` 代理到测试桥 `127.0.0.1:15203`。固定会话不是生产 JWT／菜单缓存验收。

## 启动和停止

在 backend 目录，用 JDK 21 启动明确选择的测试类：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -o -pl arte-app -am \
  -Dtest=ModelConfigurationBrowserAcceptanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmodel.browser.m5=true test
```

若本机 Mockito 需要显式 agent，沿用仓库其他集成测试的 `-DargLine=-javaagent:本机/mockito-core.jar`。不要传
`platform.test.mysql`；浏览器桥对此有拒绝断言。看到 `arte-app/target/model-m5-browser-ready` 后，在 frontend 目录运行：

```sh
node scripts/model-a2-browser/server.mjs
```

打开 `http://127.0.0.1:5234/Environment/Models`。桥最多运行 45 分钟，每次启动得到新的空目录／数据，回环供应商端口由页面显示。只使用页面显示的 `
fixture-model-key`，连接设置为页面显示的回环地址加 `/v1`。不要填写真实供应商地址或密钥。

完成后，在仓库根目录执行：

```sh
touch backend/arte-app/target/model-m5-browser-stop
```

等待 Maven 测试退出，再停止本次 Vite 进程。桥关闭 HTTP 服务、供应商和 H2，不留下业务库数据。

## 验收顺序

1. alice：平台／空间负责人，发布连接、审核方案和默认规则；在统一模型管理创建个人或租户模型，保存草稿及测试密钥、验证、发布。
2. 从精确发布版本选择目标空间；在正式工作空间详情显式准备，分别保存模型启用和费率规则。配置 B 时目录当前空间 A 应保持不变。
3. 租户模型须分别维护允许空间、模型 READ／USE、当前代次凭据消费者。bob 只属于执行空间 B，不因预算配置自动得到这些权限。权限变更按本次隔离验收的明确授权执行。
4. 切换 bob 后先等待正式目录恢复其当前空间，再打开隔离聊天。检查模型使用条件、准备本人账户、手动应用，然后发送真实回环聊天。
5. 用“核对隔离数据”查看主体／scope／精确费率、唯一准备记录、providerCalls 与 SETTLED 快照；每次供应商固定报告 3 输入、2 输出
   Token。1／2 CNY 每百万 Token 对应测试扣费 0.000007 CNY。
6. 响应丢失开关只在服务端真实成功后丢弃响应；重挂载不得自动补写。本人准备／空间保存都用原请求键确认。confirmed 后重读失败只恢复读取。

多选下拉完成选择后先按 Escape 关闭，再保存，避免点中覆盖按钮的下拉项。保存模型动作授权有二次确认弹层，必须完成确认才会提交。

publisher 是普通成员；是否有新建资格由当前模型创建政策决定，无资格时不能为了演示而额外赋权。无空间治理资格但已有模型授权的发布路径另由
M5 集成测试验证。

## 证据与限制

本轮结论、AC01～AC37、测试命令及截图见 [M5 交付说明](../../../backend/arte-app/docs/MODEL_CONFIGURATION_M5.md)
。个人与租户证据来自两次独立 H2 启动；CNY 是模拟数字。生产 JWT、在线菜单／角色缓存、外部供应商、完整 ChatNew
浏览器流程、多节点及部署验收另行执行。
