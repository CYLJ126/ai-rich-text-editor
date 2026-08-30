import {BaseParam} from "@/types";
import {ContextStrategyEnum} from "@/types/ai.conversation.type";

export interface AssistantConfig {
  [key: string]: any;

  id?: number; // 助手 ID
  name?: string; // 助手名称
  avatar?: string; // 头像 icon
  systemPrompt?: string; // 系统提示词
  contextStrategy?: ContextStrategyEnum; // 上下文策略：WINDOW/SUMMARY/FULL
  contextWindow?: number; // 上下文窗口数
  modelId?: number; // 默认模型 ID
  reasoningEffort?: string; // 推理力度
  textType?: string; // 文本类型
  temperature?: number | string; // 温度参数（后端 BigDecimal）
  maxTokens?: number; // 最大生成 token 数
  topP?: number | string; // Top P 参数
  topK?: number; // Top K 参数
  presencePenalty?: number | string; // 存在惩罚参数
  frequencyPenalty?: number | string; // 频率惩罚参数
  globalMemoryFlag?: boolean; // 是否开启全局记忆功能
  queryRewriteFlag?: boolean; // 是否开启查询重写功能
  extraParam?: string; // 额外参数（JSON 字符串）
  knowledgeBaseId?: string; // 关联知识库 ID
  sortOrder?: number; // 排序
  status?: number; // 状态: 1-启用；3-禁用
  pinFlag?: boolean; // 是否置顶
  defaultFlag?: boolean; // 是否默认助手
  description?: string; // 助手描述
  createBy?: string; // 创建人id
  updateBy?: string; // 更新人id
  createTime?: string; // 创建时间
  updateTime?: string; // 更新时间
}

export interface AssistantParam extends BaseParam {

}
