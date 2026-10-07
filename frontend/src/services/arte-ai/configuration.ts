import {type AiRequestOptions, postResult} from './request';
import type {ChatConfigurationResponse, DiscoverChatOptionsRequest} from './types';

export function discoverChatOptions(data: DiscoverChatOptionsRequest, options?: AiRequestOptions) {
  return postResult<ChatConfigurationResponse>('configuration/discoverChatOptions', data, options);
}
