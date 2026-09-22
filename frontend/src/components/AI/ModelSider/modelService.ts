import {jsonPost} from '@/services/ant-design-pro/api';

export interface AvailableModel {
  modelId: string;
  modelName: string;
  supportVision: boolean;
  supportFunction: boolean;
  supportThinking: boolean;
  supportSearch: boolean;
  contextWindow?: number;
  maxTokens?: number;
}

export interface AvailableModelQuery {
  provider: string;
  modelConfigId?: number;
  apiKey?: string;
  apiBaseUrl?: string;
}

export async function listAvailableModels(query: AvailableModelQuery) {
  return jsonPost('/ai/modelConfig/listAvailableModels', query) as Promise<
    AvailableModel[] | undefined
  >;
}
