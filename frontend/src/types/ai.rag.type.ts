/**
 * RAG 知识库类型
 * - article：文章知识库
 * - "plan"：计划管理知识库
 * - information：信息管理知识库
 * - general：通用知识库
 *
 */
export type KnowledgeBaseTypeEnum = 'article' | '"plan"' | 'information' | 'general';

export interface RagConfig {
  [key: string]: any;
}
