export type ContextStrategyEnum = 'window' | 'token' | 'summary' | 'full';

export type InterActionTypeEnum = 'frontend' | 'backend';

export type ConversationStatusEnum = 'active' | 'archived' | 'deleted';

export interface ConvSettings {
  temperature: number;
  topP: number;
  maxTokens: number;
  presencePenalty: number;
  frequencyPenalty: number;
  systemPrompt: string;
  streamEnabled: boolean;
  contextLength: number;
}

export type Conversation = {
  id?: number;
  convId?: string; // 会话 ID
  title?: string; // 会话标题
  assistantId?: number; // 关联助手 ID
  modelId?: number; // 当前使用模型
  extraParam?: Record<string, any>; // 当前会话模型参数（覆盖助手默认）
  systemPrompt?: string; // 当前会话系统提示词（覆盖助手默认）
  knowledgeBaseId?: string; // 关联知识库 ID（覆盖助手默认）
  contextStrategy?: ContextStrategyEnum; // 上下文策略：WINDOW/SUMMARY/FULL
  contextWindow?: number; // 上下文窗口大小（消息数）
  reasoningEffort?: string; // 推理力度
  textType?: string; // 文本类型
  temperature?: number; // 温度
  maxTokens?: number; // 最大生成 token 数
  topP?: number; // Top P 参数
  topK?: number; // Top K 参数
  presencePenalty?: number; // 存在惩罚
  frequencyPenalty?: number; // 频率惩罚
  globalMemoryFlag?: boolean; // 是否开启全局记忆功能
  queryRewriteFlag?: boolean; // 是否开启查询重写功能
  status?: ConversationStatusEnum; // 状态：ACTIVE/ARCHIVED/DELETED
  pinFlag?: boolean; // 是否置顶
  scene?: string; // 场景：CHAT/ARTICLE
  interActionType?: InterActionTypeEnum; // 交互类型：FRONTEND/BACKEND
  lastMessageId?: number; // 最后一条消息ID
  lastMessageAt?: string; // 最后消息时间
  lastMessageDigest?: string; // 最后消息摘要
  messageCount?: number; // 消息总数
  defaultFlag?: boolean; // 是否默认助手
  createBy?: string; // 创建人
  updateBy?: string; // 更新人
  createTime?: string; // 创建时间
  updateTime?: string; // 更新时间
}

export interface ConversationUpsertDto {
  convId: string;
  title?: string;
  assistantId?: number;
  modelId?: number;
  knowledgeBaseId?: string;
  contextStrategy?: ContextStrategyEnum;
  contextWindow?: number;
  systemPrompt?: string;
  extraParam?: Record<string, any>;
  reasoningEffort?: string; // 推理力度
  textType?: string; // 文本类型
  temperature?: number; // 温度
  maxTokens?: number; // 最大生成 token 数
  topP?: number; // Top P 参数
  topK?: number; // Top K 参数
  presencePenalty?: number; // 存在惩罚
  frequencyPenalty?: number; // 频率惩罚
  globalMemoryFlag?: boolean; // 是否开启全局记忆功能
  queryRewriteFlag?: boolean; // 是否开启查询重写功能
}
