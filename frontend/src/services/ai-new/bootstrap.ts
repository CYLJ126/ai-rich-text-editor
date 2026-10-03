import type {ChatBootstrap} from '@/types/ai-new/conversation';
import {requestAiNew} from './request';
import {bootstrapSchema, readContract} from './contracts';

export async function getChatBootstrap(signal?: AbortSignal): Promise<ChatBootstrap> {
  return readContract(bootstrapSchema, await requestAiNew('/chat/bootstrap', {signal}));
}
