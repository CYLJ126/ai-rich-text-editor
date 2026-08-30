// ─── 检索增强 ───

export interface RagChunk {
  /** 实体名称 */
  entity: string;
  /** 相关段落摘要 */
  summary: string;
  /** 原文片段（可选） */
  excerpt?: string;
}

/**
 * RAG 检索接口，业务方自行实现后传入
 * @param entities 从上下文窗口中提取的实体列表
 * @param fullText 全文（用于全文索引）
 * @returns 相关段落摘要列表
 */
export type RagRetriever = (entities: string[], fullText: string) => Promise<RagChunk[]>;

// ─── 补全状态 ───

export type CompletionStatus =
  | 'idle'
  | 'loading'   // 已触发，等待第一个 token
  | 'streaming' // 正在流式接收
  | 'done'      // 流结束，等待用户确认
  | 'error';

export interface CompletionState {
  status: CompletionStatus;
  /** 触发位置（"//" 开始前的文档位置） */
  triggerPos: number;
  /** "//" 结束位置 */
  triggerEnd: number;
  /** 当前已接收的补全文本 */
  text: string;
  /** 错误信息 */
  error?: string;
}

// ─── 扩展选项 ───

export interface AiCompletionOptions {
  // ─── 开关 ───
  /** 是否启用 AI 补全，默认 true */
  enabled?: boolean;

  // ─── 触发配置 ───
  /** 防抖延迟（ms），默认 400 */
  debounceMs?: number;
  /** 手动触发快捷键，默认 Ctrl+Space */
  manualTriggerKey?: string;

  /** 是否优先取当前章节全部内容，默认 true */
  preferCurrentSection?: boolean;

  // ─── 检索增强 ───
  ragRetriever?: RagRetriever;

  // ─── 回调 ───
  onCompletionStart?: (triggerPos: number) => void;
  onCompletionAccept?: (text: string) => void;
  onCompletionDismiss?: () => void;
  onError?: (error: Error) => void;
}
