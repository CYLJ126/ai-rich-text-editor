# 新聊天第一步：基础接入与会话管理

## 已实现

入口 `/AI/Chat`，后端基础路径 `/arte/api/ai-new`。与旧 `/AI/ChatManagement` 独立，沿用现有登录、Token 注入、主题、React Query
与后端菜单。

- 从服务端读取当前可用空间、固定默认模型、外发目的地与用途、输入／输出上限；不推导个人空间，不保存或接收密钥。
- 会话创建、列表、标题检索、分页、详情、重命名和软删除。
- 当前空间及会话通过 URL 定位，刷新后恢复；只向服务端发送租户／空间 ID，不发送初始化对象中的权限展示字段。
- 创建／命名／删除没有自动重试；命名与删除使用实际预期版本，冲突后刷新，提交结果未知时先核对列表。
- 查询缓存按用户／租户／空间隔离，退出登录清理新模块缓存。权限检查失败时隐藏旧数据，离开会话管理空间后的迟到命令响应不再更新界面或缓存。
- 简体中文、繁体中文、英语文案；窄屏采用上下布局。

第一步只管理会话并展示模型信息，没有发送消息、轮询生成结果或触发模型外发，也不会产生模型调用费用。

## 目录

- `src/pages/AI/Chat`：页面场景、URL 定位、会话管理界面与专用组件。
- `src/services/ai-new`：直接值响应、稳定错误、会话请求及运行时响应契约校验。
- `src/types/ai-new`：当前 HTTP 值契约。
- `src/features/ai-chat`：查询键、错误呈现、查询及会话命令 hooks。
- `src/locales/*/aiNew.ts`：新页面文案。

后端业务和持久化仍沿用现有新聊天服务，初始化读取实现位于 `arte-app`，未向 base 或 ai-new 引入 Web／数据库依赖。

## 初始化接口

`GET /api/ai-new/chat/bootstrap`，使用现有登录会话。返回直接的 JSON 对象：

```json
{
  "enabled": true,
  "unavailableReason": null,
  "workspaces": [
    {"tenantId": "实际租户", "workspaceId": "实际空间", "allowedActions": ["resource.ai_process"]}
  ],
  "defaultModel": {
    "name": "运维配置模型名称",
    "providerId": "compatible-chat",
    "bindingRef": {"definitionType": "ai-binding", "definitionId": "default-model", "version": "v1"},
    "destination": "https://受控服务/v1/chat/completions",
    "purpose": "model.generate",
    "inputTypes": ["text"],
    "streaming": false,
    "contextMaxBytes": 8192,
    "maxOutputTokens": 2048
  }
}
```

聊天关闭时仍可查询开关状态，返回 `enabled=false / CHAT_DISABLED`，没有空间和模型信息。 聊天开启但成员或 AI 应用许可缺失时返回
`NO_ACCESS` 和空空间列表，不暴露模型配置。 会话管理只要求 AI 应用许可，不要求用户先确认外发；`allowedActions`
是当前应用／绑定许可展示，不是连接、预算或一次请求的最终授权。新命令仍实时校验。

目前只返回固定默认模型配置覆盖的一个空间，并非完整多租户空间目录。 此接口只读取身份与策略，不登记任务、同意、预算，不探测供应商，也不读取凭据环境变量。

## 接入现有环境

1. 按后端 `MINIMUM_MODEL_CALL.md` 和 `MINIMUM_CHAT_SERVICE.md` 准备安全、执行、模型及聊天表和实际配置。
2. 启用安全桥接、单实例执行支撑、模型及聊天配置。第一步也依赖这些服务接线，但管理会话不会调用模型。
3. 在实际数据库执行 `backend/arte-app/scripts/arte-ai-new-chat-menu-dml-mysql.sql`。脚本可重复执行，保留旧入口及已存在菜单的状态，默认为
   admin 登记 `AIChat` 菜单。
4. 其他角色通过现有 RBAC 管理分配 `AIChat` 菜单；菜单不自动授予空间、AI、外发或预算许可。
5. 登出后重新登录，刷新当前用户菜单。进入“新聊天”，或直接访问 `/AI/Chat`；直接访问也检查页面入口许可。

本次没有执行实际数据库脚本、开启运行环境配置或调用真实供应商。Docker 初始化脚本及旧 AI 部署路径没有自动切换。

## 验收

1. 功能关闭时显示未启用；角色缺少菜单、成员或应用许可时显示对应不可用状态。
2. 创建会话，确认列表和详情立即更新，URL 保存当前会话；刷新后仍能读取。
3. 按标题检索，验证 `%`／`_` 按字面字符搜索；每页 20 条，是否有下一页由多读取一条判断，不伪造总数。
4. 重命名与删除；通过另一个客户端修改版本后，原页面旧版本操作应冲突并刷新，不覆盖新内容。
5. 权限撤销后重新查询，不显示缓存详情；篡改 URL 中的空间不能触发未经授权空间的会话请求。
6. 修改成功后检查数据库版本与删除标记，确认没有增加模型执行或预算消耗。

页面和请求测试使用真实 Ant Design 组件及请求契约，HTTP 响应由测试适配器提供；后端使用实际身份／权限和 H2 存储验证。生产
MySQL、完整登录／菜单及浏览器视觉验收仍需在已配置环境执行。

后续第二步在该页面加入消息输入、外发确认、多轮历史与执行结果观察。
