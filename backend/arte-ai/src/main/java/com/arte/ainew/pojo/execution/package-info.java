/**
 * AI 能力调用与执行生命周期的数据契约。
 *
 * <p>承载类型化调用信封及 AI 专有的 Invocation／Attempt、执行选项、状态与结果引用。
 * 受理、执行、尝试、取消请求与终态分别表达；能力专有输入输出保留在对应数据包中。
 *
 * <p>主体与授权上下文由可信服务端构建；执行上下文复用
 * {@link com.arte.ainew.common.execution.ExecutionContext}，后续可迁移独立公共契约模块。
 * 不在此重复定义，不携带凭据、连接池、线程或进程内取消信号等运行资源。
 */
package com.arte.ainew.pojo.execution;
