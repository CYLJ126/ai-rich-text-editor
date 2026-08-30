export interface StreamChunk {
  messageId?: string;
  delta?: string | null;
  thinkDelta?: string | null;
  done: boolean;
  finishReason?: string | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  thinkTokens?: number | null;
  error?: {
    message: string;
    code?: string;
  } | null;
  metadata?: {
    promptTokens?: number;
    completionTokens?: number;
    totalTokens?: number;
  } | null;
}

export interface StreamChatCallbacks {
  /** 第一个 chunk 到来时，返回后端真实的 messageId */
  onMessageId?: (messageId: string) => void;
  onThinking?: (delta: string) => void;
  onContent?: (delta: string) => void;
  onMetadata?: (meta: StreamChunk['metadata']) => void;
  onDone?: () => void;
  onError?: (err: StreamChunk['error']) => void;
}

export type StreamCallback = {
  onThinking?: (delta: string) => void;
  onContent?: (delta: string) => void;
  onMetadata?: (metadata: StreamChunk['metadata']) => void;
  onDone?: () => void;
  onError?: (err: StreamChunk['error']) => void;
};
