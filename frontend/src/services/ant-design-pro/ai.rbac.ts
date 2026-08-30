import {jsonGet, jsonPost, jsonPostList} from './api';
import {AssistantConfig, ConversationUpsertDto, ModelConfig} from "@/types/ai.type";
import {request} from "@@/exports";

/**  ----------------- AssistantController start ----------------- */
/** 新增助手 POST /arte/ai/assistant/addAssistant */
export async function addAssistant(param: AssistantConfig) {
  return jsonPost('/ai/assistant/addAssistant', param);
}

/** 编辑助手 POST /arte/ai/assistant/updateAssistant */
export async function updateAssistant(param: AssistantConfig) {
  return jsonPost('/ai/assistant/updateAssistant', param);
}

/** 删除助手 DELETE /arte/ai/assistant/deleteAssistant */
export async function deleteAssistant(id: number) {
  return jsonPost(`/ai/assistant/deleteAssistant`, {id});
}

/** 获取助手 GET /arte/ai/assistant/getAssistant */
export async function getAssistant(id: number | null, defaultFlag?: boolean) {
  return jsonPost(`/ai/assistant/getAssistant`, {id, defaultFlag});
}

/** 获取助手列表 GET /arte/ai/assistant/listAssistants */
export async function listAssistants(param: any) {
  return jsonPostList('/ai/assistant/listAssistants', param);
}

/** 置顶/取消置顶 POST /arte/ai/assistant/toggleAssistantPin */
export async function toggleAssistantPin(id: number, pinFlag: boolean) {
  return jsonPost('/ai/assistant/toggleAssistantPin', {id, pinFlag});
}

/** 切换状态 POST /arte/ai/assistant/toggleAssistantStatus */
export async function toggleAssistantStatus(id: number, status: number) {
  return jsonPost('/ai/assistant/toggleAssistantStatus', {id, status});
}

/** 设置默认助手 POST /arte/ai/assistant/setAsDefaultAssistant */
export async function setAsDefaultAssistant(id: number) {
  return jsonPost('/ai/assistant/setAsDefaultAssistant', {id});
}
/**  ----------------- AssistantController end ----------------- */


/**  ----------------- ModelConfigController start ----------------- */
/** 新增模型配置 POST /arte/ai/modelConfig/addModelConfig */
export async function addModelConfig(param: ModelConfig) {
  return jsonPost('/ai/modelConfig/addModelConfig', param);
}

/** 编辑模型配置 POST /arte/ai/modelConfig/updateModelConfig */
export async function updateModelConfig(param: ModelConfig) {
  return jsonPost('/ai/modelConfig/updateModelConfig', param);
}

/** 删除模型配置 DELETE /arte/ai/modelConfig/deleteModelConfig */
export async function deleteModelConfig(id: number) {
  return jsonPost(`/ai/modelConfig/deleteModelConfig`, {id});
}

/** 获取模型配置 GET /arte/ai/modelConfig/getModelConfig */
export async function getModelConfig(id: number | null, defaultFlag?: boolean) {
  return jsonPost(`/ai/modelConfig/getModelConfig`, {id, defaultFlag});
}

/** 获取模型配置列表 GET /arte/ai/modelConfig/listModelConfigs */
export async function listModelConfigs(param: any) {
  return jsonPostList('/ai/modelConfig/listModelConfigs', param);
}

/** 置顶/取消置顶 POST /arte/ai/modelConfig/toggleModelConfigPin */
export async function toggleModelConfigPin(id: number, pinFlag: boolean) {
  return jsonPost('/ai/modelConfig/toggleModelConfigPin', {id, pinFlag});
}

/** 设为默认 POST /arte/ai/modelConfig/setAsDefaultModelConfig */
export async function setAsDefaultModelConfig(id: number) {
  return jsonPost('/ai/modelConfig/setAsDefaultModelConfig', {id});
}

/** 切换状态 POST /arte/ai/modelConfig/toggleModelConfigStatus */
export async function toggleModelConfigStatus(id: number, status: number) {
  return jsonPost('/ai/modelConfig/toggleModelConfigStatus', {id, status});
}
/**  ----------------- ModelConfigController end ----------------- */


/**  ----------------- MetaInfoController start ----------------- */
/** 查询知识库类型列表 GET /arte/ai/metaInfo/listKnowledgeBaseTypes */
export async function listKnowledgeBaseTypes() {
  return jsonGet('/ai/metaInfo/listKnowledgeBaseTypes');
}

/** 查询模型提供商列表 GET /arte/ai/metaInfo/listModelProviders */
export async function listModelProviders() {
  return jsonGet('/ai/metaInfo/listModelProviders');
}

/** 查询模型类型列表 GET /arte/ai/metaInfo/listModelTypes */
export async function listModelTypes() {
  return jsonGet('/ai/metaInfo/listModelTypes');
}

/** 查询上下文策略类型列表 GET /arte/ai/metaInfo/listContextStrategies */
export async function listContextStrategies() {
  return jsonGet('/ai/metaInfo/listContextStrategies');
}

/** 查询文本类型列表 GET /arte/ai/metaInfo/listTextTypes */
export async function listTextTypes() {
  return jsonGet('/ai/metaInfo/listTextTypes');
}

/** 查询推理力度列表 GET /arte/ai/metaInfo/listReasoningEfforts */
export async function listReasoningEfforts() {
  return jsonGet('/ai/metaInfo/listReasoningEfforts');
}
/**  ----------------- MetaInfoController end ----------------- */


/**  ----------------- ConversationController start ----------------- */
/** 创建会话 POST /arte/ai/conversation/createConversation */
export async function createConversation(param: any) {
  return jsonPost('/ai/conversation/createConversation', param);
}

/** 更新会话 POST /arte/ai/conversation/fullUpdateConversation */
export async function fullUpdateConversation(param: ConversationUpsertDto) {
  return jsonPost('/ai/conversation/fullUpdateConversation', param);
}

/** 置顶 / 取消置顶 POST /arte/ai/conversation/toggleConversationPin */
export async function toggleConversationPin(convId: string, pinFlag: boolean) {
  return jsonPost('/ai/conversation/toggleConversationPin', {convId, pinFlag});
}

/** 切换会话状态 POST /arte/ai/conversation/toggleConversationStatus */
export async function toggleConversationStatus(convId: string, status: boolean) {
  return jsonPost('/ai/conversation/toggleConversationStatus', {convId, status});
}

/** 查询会话列表 POST /arte/ai/conversation/listConversations */
export async function listConversations(param: any) {
  return jsonPostList('/ai/conversation/listConversations', param);
}

/** 更新会话 POST /arte/ai/conversation/getConversationDetail */
export async function getConversationDetail(convId: string) {
  return jsonPost(`/ai/conversation/getConversationDetail/${convId}`, null);
}

/** 删除会话 POST /arte/ai/conversation/deleteConversation */
export async function deleteConversation(convId: string) {
  return jsonPost(`/ai/conversation/deleteConversation/${convId}`, null);
}
/**  ----------------- ConversationController end ----------------- */


/**  ----------------- MessageController start ----------------- */
/** 查询会话消息列表 POST /arte/ai/message/listMessages */
export async function listMessages(param: any) {
  return jsonPostList('/ai/message/listMessages', param);
}

/** 批量删除消息 POST /arte/ai/message/batchDeleteMessages */
export async function batchDeleteMessages(convId: string, messageIds: string[]) {
  return jsonPost('/ai/message/batchDeleteMessages', {convId, messageIds});
}

/** 批量删除消息 POST /arte/ai/message/toggleMessageLike */
export async function toggleMessageLike(messageId: string, likeStatus: number) {
  return jsonPost('/ai/message/toggleMessageLike', {messageId, likeStatus});
}

/** 获取消息分支列表 */
export async function getMessageBranches(messageId: string): Promise<any> {
  return request(`/arte/ai/message/branches/${messageId}`, {
    method: 'GET',
  });
}

/**  ----------------- MessageController end ----------------- */
