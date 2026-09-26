/**
 * AI 工具领域契约。
 *
 * <h2>调用主流程</h2>
 * <pre>
 * LLM / REST / MCP / Workflow
 *            ⬇︎
 *       ToolGateway                 动态 JSON 边界
 *            ⬇︎
 *       ToolRegistry                按 namespace/name/version 解析
 *            ⬇︎
 *       ToolExecutor                统一执行管道
 *            ⬇︎
 *   validate -> auth -> guardrail -> approval -> execution mode
 *            ❚                                  |
 *            ❚                     BLOCKING / NON_BLOCKING
 *            ❚                                  |
 *            ❚                         limit/timeout/retry
 *            ❚
 *            +---------------------- DEFERRED -> durable ToolTask
 *            ⬇︎
 *       Tool.execute                强类型业务能力
 *            ⬇︎
 *   output validation -> trace/audit/metrics -> ToolResult
 * </pre>
 *
 * <p>工作流程是预定义的图，Agent 则由模型在运行时动态决定下一步。
 * 两者都应经过 {@link com.arte.ai.api.tool.ToolGateway} 使用同一套工具安全、追踪和评估机制。
 */
package com.arte.ai.api.tool;
