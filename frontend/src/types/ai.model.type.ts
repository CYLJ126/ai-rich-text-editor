export type ModelProviderEnum = 'deepseek' | 'qianwen' | 'openai' | 'openrouter';
export type ModelTypeEnum = 'CHAT' | 'EMBEDDING' | 'IMAGE' | 'AUDIO';

export interface ModelConfig {
  [key: string]: any;

  id?: number; // 主键 ID
  provider?: ModelProviderEnum; // 模型提供商
  modelId?: string; // 模型 ID
  modelName?: string; // 模型显示名称
  modelType?: ModelTypeEnum; // 模型类型：CHAT/EMBEDDING/IMAGE/AUDIO
  apiKey?: string; // API Key（加密存储）
  apiBaseUrl?: string; // 自定义 API 地址
  apiVersion?: string; // API 版本号
  orgId?: number; // 组织 ID
  defaultParam?: Record<string, any>; // 默认模型参数
  contextWindow?: number; // 上下文窗口大小（token 数）
  maxTokens?: number; // 最大输出 token 数
  supportVision?: boolean; // 是否支持视觉
  supportFunction?: boolean; // 是否支持函数调用
  supportThinking?: boolean; // 是否支持深度思考
  supportSearch?: boolean; // 是否支持联网搜索
  supportPromptCaching?: boolean; // 是否支持提示缓存
  inputUnitPrice?: number; // 输入单位价格
  outputUnitPrice?: number; // 输出单位价格
  priceCurrency?: string; // 货币单位
  timeoutSeconds?: number; // 请求超时时间（秒）
  maxRetries?: number; // 最大重试次数
  icon?: string; // 图标
  status?: number; // 状态: 1-启用；3-禁用
  pinFlag?: boolean; // 是否置顶
  proxy?: string; // 代理，如 127.0.0.1:7897，不配则不使用代理，用户配 127.0.0.1 表示走服务器代理
  requestsPerMinute?: number; // 每分钟最大请求数（RPM）
  tokensPerMinute?: number; // 每分钟最大 Token 数（TPM）
  dailyRequestLimit?: number; // 每日最大请求数
  concurrencyLimit?: number; // 并发请求数限制
  sortOrder?: number; // 排序
  defaultFlag?: boolean; // 是否默认模型
  description?: string; // 描述
  createBy?: string; // 创建人 ID
  updateBy?: string; // 更新人 ID
  createTime?: string; // 创建时间
  updateTime?: string; // 更新时间
}
