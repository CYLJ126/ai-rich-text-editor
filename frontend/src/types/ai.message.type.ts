import {ModelConfig} from "@/types/ai.model.type";

// ─── 枚举 ─────────────────────────────────────────────────────────────────────
export type MessageRoleEnum = 'user' | 'assistant' | 'system' | 'tool';

export type MessageStatusEnum = 'pending' | 'streaming' | 'completed' | 'failed' | 'stopped';

// ─── 消息文件 ───
export interface MessageAttachment {
  attachmentId: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  fileUrl: string;
  thumbnailUrl?: string;
}

// ─── 发送参数 ───
export interface SendMessageParams {
  content: string;
  quotedMessage?: { messageId: string; content: string }; // 引用消息
  attachments?: MessageAttachment[]; // 文件列表（图片也在内）
  model?: ModelConfig; // 模型配置
  enableSearch?: boolean; // 是否启用搜索
  enableVision?: boolean; // 是否启用视觉
  enableThinking?: boolean; // 是否启用思考
  generateImage?: boolean; // 是否生成图片
}

// ─── 消息记录 ───
export interface Message {
  id?: number;
  messageId: string;
  convId: string;
  parentMessageId?: string;
  branchId?: string;
  branchIndex?: number;
  role: MessageRoleEnum;
  content: string;
  optimizedContent?: string;
  textType?: string;
  modelId?: number;
  modelParam?: Record<string, unknown>;
  reasoningContent?: string;
  toolCalls?: Record<string, unknown>[];
  quotedMessageId?: string;
  quotedSnapshot?: string;
  status?: MessageStatusEnum;
  likeStatus?: number; // 1=like, -1=dislike, 0=none
  deleteFlag?: boolean;
  finishReason?: string;
  promptToken?: number;
  completionToken?: number;
  totalToken?: number;
  reasoningToken?: number;
  latencyMs?: number;
  firstTokenMs?: number;
  promptCost?: number;
  completionCost?: number;
  currency?: string;
  errorCode?: string;
  errorMessage?: string;
  retryCount?: number;
  requestId?: string;
  attachments?: MessageAttachment[];
  branches?: Message[];
  createBy?: string; // 创建人id
  updateBy?: string; // 更新人id
  createTime?: string; // 创建时间
  updateTime?: string; // 更新时间
}
