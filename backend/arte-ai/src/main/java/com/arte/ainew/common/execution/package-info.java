/**
 * 执行身份与授权的共用数据契约，不依赖 AI 专有类型、Spring 或 Reactor。
 * 这些对象是服务端内部契约，不应直接绑定为外部请求体，也不代表授权永久有效。
 * TODO 为保持本阶段新旧隔离，暂放在 ainew；建立独立公共契约模块后可整体迁移。
 */
package com.arte.ainew.common.execution;
