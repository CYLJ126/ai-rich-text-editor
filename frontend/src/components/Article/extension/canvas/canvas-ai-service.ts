import {request} from '@umijs/max';
import {i18nText} from '@/utils/i18n';
import type {MessageAttachment} from '@/types/ai.type';
import type {CanvasAiMode} from './canvas-ai';

export interface CanvasImageRecord {
  id: number;
  fileName: string;
  fileType: string;
  fileSize: number;
  accessUrl: string;
}

export function toCanvasAttachment(image: CanvasImageRecord): MessageAttachment {
  return {attachmentId: String(image.id), fileName: image.fileName, fileType: image.fileType,
    fileSize: image.fileSize, fileUrl: image.accessUrl};
}

export async function uploadCanvasChatImage(file: File, convId: string, messageId: string) {
  const data = new FormData();
  data.append('file', file);
  data.append('convId', convId);
  data.append('messageId', messageId);
  const result = await request<{success: boolean; data: CanvasImageRecord; desc?: string}>(
    '/arte/richText/file/uploadChatImage', {method: 'POST', data, requestType: 'form'},
  );
  if (!result.success) throw new Error(result.desc || i18nText('app.common.services.upload.d77586ac'));
  return toCanvasAttachment(result.data);
}

export async function configureCanvasConversation(convId: string, modelId: number, mode: CanvasAiMode) {
  const result = await request<{success: boolean; desc?: string}>('/arte/ai/conversation/fullUpdateConversation', {
    method: 'POST', data: {convId, modelId, contextStrategy: 'window', contextWindow: mode === 'create' ? 0 : 12,
      textType: 'markdown', reasoningEffort: 'none', queryRewriteFlag: false, globalMemoryFlag: false},
  });
  if (!result.success) throw new Error(result.desc || i18nText('app.article.canvas.ai.historyError'));
}
