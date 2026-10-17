import type {AiScope, ConversationTurnResponse} from './types';

/** S3 后端契约；前端交互在第四阶段接入。 */
export interface SearchConversationsRequest {
  scope: AiScope;
  query: string;
  groupIds?: string[] | null;
  size: number;
  cursor?: string | null;
}

export interface ConversationSearchSnippet {
  text: string;
  /** UTF-16 左闭右开区间；text 供列表与 Popover 共用。 */
  highlights: { start: number; end: number }[];
}

interface SearchItemBase {
  entryKey: string;
  conversationId: string;
  title: string;
  snippet: ConversationSearchSnippet;
}

export type ConversationSearchItem = SearchItemBase & (
  | {
  matchKind: 'TITLE'; canLocateMessage: false; turnId: null; messageId: null;
  invocationId: null; candidateOrdinal: null; partial: false
}
  | {
  matchKind: 'MESSAGE'; canLocateMessage: true; turnId: string; messageId: string;
  invocationId: string | null; candidateOrdinal: number | null; partial: boolean
}
  );

export interface ConversationSearchPage {
  searchSessionId: string;
  records: ConversationSearchItem[];
  previousCursor: string | null;
  nextCursor: string | null;
  firstCursor: string;
  endReason: 'END' | null;
  partialPage: boolean;
  total: null;
}

export interface CloseConversationSearchRequest {
  scope: AiScope;
  searchSessionId: string
}

export type QueryTurnsAroundRequest = { scope: AiScope; conversationId: string; size: number } & (
  | { turnId: string; expectedVersion: number; historyCursor?: null }
  | { turnId?: null; expectedVersion?: null; historyCursor: string }
  );

export interface ConversationTurnsAroundPage {
  conversationVersion: number;
  records: ConversationTurnResponse[];
  earlierCursor: string | null;
  laterCursor: string | null;
}
