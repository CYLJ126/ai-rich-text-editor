/**
 * 执行上下文的可信入口构建、响应式传播和阻塞兼容。
 * 数据契约在 common.execution；进程内运行对象不持久化。
 * 配置和入口显式组合，不注册全局 Hook，也不自动扫描或改变旧 ai 的行为。
 */
package com.arte.ainew.context;
