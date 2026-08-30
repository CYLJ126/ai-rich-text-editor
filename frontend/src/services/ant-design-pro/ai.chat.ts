import {request} from "@@/exports";
import type {StreamCallback, StreamChatCallbacks, StreamChunk} from '@/types/ai.type';

/** 流式交互请求地址 */
export const STREAM_CHAT_URL = '/nip/ai/chat/streamChat';

/** 根据文章生成 AI 总结 */
export const STREAM_ARTICLE_SUMMARY_URL = '/nip/richText/article/generateArticleSummary';

/** 编辑器内 AI 流式补全（续写） */
export const STREAM_COMPLETION_URL = '/nip/ai/chat/streamGenerate';

/**
 * 处理单个 SSE chunk
 */
function handleChunk(
  chunk: StreamChunk,
  callbacks: StreamChatCallbacks,
  state: { messageIdReported: boolean },
): void {
  const {onMessageId, onThinking, onContent, onMetadata, onDone, onError} = callbacks;

  // 后端返回错误字段
  if (chunk.error) {
    onError?.(chunk.error);
    return;
  }

  // 第一次收到真实 messageId 时上报（用于替换占位 ID）
  if (chunk.messageId && !state.messageIdReported) {
    state.messageIdReported = true;
    onMessageId?.(chunk.messageId);
  }

  // 思考内容（thinkDelta 不为 null/空时）
  if (chunk.thinkDelta) {
    onThinking?.(chunk.thinkDelta);
  }

  // 正文增量（delta 不为 null/空时）
  if (chunk.delta) {
    onContent?.(chunk.delta);
  }

  // 最后一包：done=true，携带 token 统计
  if (chunk.done) {
    if (
      chunk.promptTokens != null ||
      chunk.completionTokens != null ||
      chunk.totalTokens != null
    ) {
      onMetadata?.({
        promptTokens: chunk.promptTokens ?? undefined,
        completionTokens: chunk.completionTokens ?? undefined,
        totalTokens: chunk.totalTokens ?? undefined,
      });
    }
    onDone?.();
  }
}

/**
 * 流式聊天 - 正确解析 SSE 格式
 * 后端返回格式:
 *   data:{"messageId":"xxx","delta":"text","thinkDelta":null,"done":false}
 *   data:{"messageId":"xxx","delta":"","done":true,"promptTokens":453,...}
 */
export async function streamChat(
  url: string,
  params: any,
  callbacks: StreamChatCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const {onDone, onError} = callbacks;
  const token = localStorage.getItem('user_token');
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 如果需要鉴权 token，在这里加
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(params),
      signal,
    });
  } catch (err: any) {
    if (err?.name === 'AbortError') throw err;
    onError?.({message: err?.message ?? '网络请求失败'});
    return;
  }

  if (!response.ok) {
    onError?.({message: `HTTP ${response.status}: ${response.statusText}`});
    return;
  }
  // 用 ReadableStream + TextDecoder 逐块读取
  const reader = response.body?.getReader();
  if (!reader) {
    onError?.({message: '无法获取响应流'});
    return;
  }

  const decoder = new TextDecoder('utf-8');
  // SSE 数据可能跨多个 chunk，需要缓冲区拼接
  let buffer = '';

  // 跨 chunk 共享的状态，避免重复上报 messageId
  const state: { messageIdReported: boolean } = {messageIdReported: false};

  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      // 将二进制数据解码为字符串，追加到缓冲区
      buffer += decoder.decode(value, {stream: true});

      // SSE 事件之间用 \n\n 分隔，单行用 \n
      // 这里按 \n 逐行处理，兼容两种情况
      const lines = buffer.split('\n');
      // 最后一个元素可能是不完整的行，保留到下一次
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        const trimmed = line.trim();
        // 空行：SSE 事件分隔符，直接跳过
        if (!trimmed) continue;
        // 注释行
        if (trimmed.startsWith(':')) continue;
        // 处理 data: 或 data:{...}（后端不带空格）
        if (trimmed.startsWith('data:')) {
          const data = trimmed.slice(5).trim(); // 兼容有无空格
          // 解析 JSON
          try {
            const chunk: StreamChunk = JSON.parse(data);
            handleChunk(chunk, callbacks, state);
          } catch (parseErr) {
            console.warn('[SSE] JSON 解析失败:', data, parseErr);
            // 不中断流，继续处理后续数据
          }
        }
      }
    }

    // flush decoder 剩余字节
    const remaining = decoder.decode(undefined, {stream: false});
    if (remaining) {
      buffer += remaining;
    }

    // 流正常结束（后端通过 done:true 通知，handleChunk 内已调用 onDone）
    // 兜底：如果没有收到 done:true，主动触发
    onDone?.();
  } catch (err: any) {
    if (err?.name === 'AbortError') throw err;
    onError?.({message: err?.message ?? '读取流失败'});
  } finally {
    reader.releaseLock();
  }
}

/**  ----------------- ChatController start ----------------- */
/** 后端代理流式聊天 */
export function backStreamChat(request: any, callbacks: StreamCallback, signal?: AbortSignal): Promise<void> {
  return streamChat(`/nip/ai/chat/streamChat`, request, callbacks, signal);
}

/** 重新生成 */
export function streamRegenerate(request: any, callbacks: StreamCallback, signal?: AbortSignal): Promise<void> {
  return streamChat(`/nip/ai/chat/regenerate`, request, callbacks, signal);
}

/** 编辑并重发 */
export function streamEditResend(request: any, callbacks: StreamCallback, signal?: AbortSignal): Promise<void> {
  return streamChat(`/nip/ai/chat/edit-resend`, request, callbacks, signal);
}

/** 前端直连模式保存交互数据 */
export async function saveFrontendInteraction(dto: any): Promise<any> {
  return request(`/nip/ai/chat/frontend/save`, {
    method: 'POST',
    data: dto,
  });
}
/**  ----------------- ChatController end ----------------- */
