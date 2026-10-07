/** 新 AI HTTP 契约；与旧版 AI 接口及数据库实体类型分开。 */
export interface AiScope {
  tenantId: string;
  workspaceId: string;
}

export interface DiscoverChatOptionsRequest {
  scope: AiScope;
}

/** 后端公开的配置选择白名单，金额由 getBudget 单独查询。 */
export interface ChatModelOption {
  displayName: string;
  binding: DefinitionRef<'binding'>;
  capability: DefinitionRef<'capability'>;
  contextWindowTokens: number;
  limits: {
    maxInputTokens: number;
    maxOutputTokens: number;
    maxInputBytes: number;
    maxOutputBytes: number;
    maxTimeoutSeconds: number;
  };
  defaults: {
    maxInputTokens: number;
    maxOutputTokens: number;
    timeoutSeconds: number;
  };
  budgetRefs: string[];
}

export interface ChatConfigurationResponse {
  options: ChatModelOption[];
}

export interface DefinitionRef<T extends string = string> {
  type: T;
  id: string;
  version: string;
}

export interface ResourceRef {
  resourceType: string;
  resourceId: string;
  version: string | null;
  draftId: string | null;
  rangeRef: string | null;
  contentDigest: string | null;
}

export interface AiResultMetadata {
  success: true;
  code: string;
  desc: string;
}

export interface AiResult<T> extends AiResultMetadata {
  data: T;
  statistics?: Record<string, unknown> | null;
}

/** PageView 的 records 在顶层，不在 data 内。 */
export interface AiPage<T> extends AiResultMetadata {
  records: T[];
  current: number;
  size: number;
  total: number;
  pages?: number;
}

export interface AiHttpResponse<T> {
  httpStatus: number;
  body: T;
}

/** 页码从 1 开始，size 最大 100；省略时后端默认 current=1、size=20。 */
export interface PageQuery {
  current?: number;
  size?: number;
}

export interface CreateConversationRequest {
  scope: AiScope;
  title: string;
  /** 当前最小链路只支持省略或 null。 */
  chatProfile?: DefinitionRef | null;
  /** 当前最小链路只支持省略或空数组。 */
  resources?: ResourceRef[];
}

export interface GetConversationRequest {
  scope: AiScope;
  conversationId: string;
}

export interface ListConversationsRequest {
  scope: AiScope;
  page?: PageQuery;
}

export interface QueryTurnsRequest extends ListConversationsRequest {
  conversationId: string;
  expectedVersion: number;
}

export interface ConversationResponse {
  conversationId: string;
  title: string;
  version: number;
  chatProfile: DefinitionRef | null;
  resources: ResourceRef[];
  state: 'ACTIVE' | 'DELETED';
  createdAt: string;
  updatedAt: string;
}

export interface ArtifactRef {
  artifactId: string;
  version: string;
  mediaType: string;
  contentDigest: string;
  byteSize: number;
}

// HTTP 文本部分没有 type 字段，通过 text / modality 区分。
export type MessageContent =
  | { text: string }
  | { modality: 'IMAGE' | 'AUDIO' | 'VIDEO' | 'FILE'; reference: ArtifactRef };

export interface ChatMessage {
  messageId: string;
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL';
  content: MessageContent[];
  toolCalls: {
    callId: string;
    tool: DefinitionRef<'tool'>;
    /** 当前文本链路不解释 StructuredValue 的传输结构。 */
    arguments: unknown;
  }[];
  toolCallId: string | null;
}

export interface ConversationTurnResponse {
  turnId: string;
  conversationId: string;
  sequence: number;
  parentTurnId: string | null;
  supersedesTurnId: string | null;
  userMessage: ChatMessage;
  invocationIds: string[];
  selectedInvocationId: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface GenerationOptions {
  maxOutputTokens: number;
  temperature?: number | null;
  topP?: number | null;
  stopSequences: string[];
}

export interface SubmitChatRequest extends GetConversationRequest {
  expectedVersion: number;
  text: string;
  capability: DefinitionRef<'capability'>;
  binding: DefinitionRef<'binding'>;
  budgetRef: string;
  maxInputTokens: number;
  generationOptions: GenerationOptions;
  timeoutSeconds: number;
}

/** HTTP 202 表示受理，不能据此判定回答成功。 */
export interface ChatAcceptedResponse {
  invocationId: string;
  conversationId: string;
  kind: 'INVOCATION';
  acceptedAt: string;
}

export type CapabilityKind =
  | 'GENERATION'
  | 'EMBEDDING'
  | 'MEDIA'
  | 'TOOL'
  | 'REMOTE_APPLICATION';

export type ActiveInvocationState = 'ACCEPTED' | 'QUEUED' | 'RUNNING';
export type TerminalInvocationState =
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'INTERRUPTED'
  | 'UNKNOWN';
export type InvocationState = ActiveInvocationState | TerminalInvocationState;

export interface ExecutionError {
  code: string;
  phase:
    | 'VALIDATION'
    | 'AUTHORIZATION'
    | 'ADMISSION'
    | 'DISPATCH'
    | 'INVOCATION'
    | 'OUTPUT'
    | 'PERSISTENCE'
    | 'SETTLEMENT';
  retryable: boolean;
  sideEffect: 'NONE' | 'POSSIBLE' | 'CONFIRMED';
  certainty: 'KNOWN' | 'UNKNOWN';
  correlationId: string;
}

export interface InvocationQuery {
  scope: AiScope;
  invocationId: string;
}

export interface InvocationEventsRequest extends InvocationQuery {
  /** 排他游标，首次为 0；下一页使用 nextCursor.afterSequence。 */
  afterSequence: number;
  /** 1～256。 */
  limit: number;
}

export type InvocationBudgetState =
  | 'NOT_RESERVED'
  | 'RESERVED'
  | 'PENDING_RECONCILIATION'
  | 'SETTLED'
  | 'RELEASED';

export interface InvocationStatusResponse {
  invocationId: string;
  kind: CapabilityKind;
  conversation: {
    conversationId: string;
    conversationVersion: number;
    turnId: string;
  } | null;
  state: InvocationState;
  version: number;
  /** 本次调用的预留状态；终态与账本结算可先后提交。 */
  budgetState?: InvocationBudgetState;
  activeAttemptId: string | null;
  resultAvailable: boolean;
  partial: boolean | null;
  error: ExecutionError | null;
  acceptedAt: string;
  updatedAt: string;
}

/** null 为未知用量，与已知为 0 区别对待。 */
export interface Usage {
  basis: 'UNKNOWN' | 'ESTIMATED' | 'PROVIDER_REPORTED';
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
}

export type FinishReason =
  | 'STOP'
  | 'TOOL_CALLS'
  | 'LENGTH'
  | 'CONTENT_FILTER'
  | 'OTHER';

export interface ModelResult {
  resultId: string;
  model: { providerId: string; modelId: string; revision: string | null };
  outputs: ChatMessage[];
  finishReason: FinishReason;
  complete: boolean;
  structuredOutput: unknown;
  usage: Usage;
  sources: {
    citationId: string;
    resource: ResourceRef;
    excerptDigest: string;
  }[];
}

/** 文本页面只解释 GENERATION；其他能力的结果保留原始值。 */
export type InvocationResultResponse =
  | { invocationId: string; kind: 'GENERATION'; result: { value: ModelResult } }
  | {
  invocationId: string;
  kind: Exclude<CapabilityKind, 'GENERATION'>;
  result: { value: unknown };
};

export type GenerationEvent =
  | { text: string }
  | {
  index: number;
  callId: string | null;
  toolName: string | null;
  argumentsFragment: string;
}
  | { usage: Usage }
  | { reason: FinishReason };

export interface ResultRef {
  resultId: string;
  resultType: string;
  schemaVersion: number;
  contentDigest: string;
  partial: boolean;
}

interface ExecutionEventMetadata {
  schemaVersion: number;
  executionId: string;
  attemptId: string | null;
  sequence: number;
  occurredAt: string;
  payloadType: string;
  payloadVersion: number;
}

export type ExecutionEvent = ExecutionEventMetadata &
  (
    | {
    kind: 'ACCEPTED' | 'STARTED' | 'CHECKPOINT';
    payload: { state: ActiveInvocationState };
  }
    | { kind: 'OUTPUT'; payload: { events: GenerationEvent[] } }
    | {
    kind: 'BUDGET_CHANGED';
    payload: {
      state: Exclude<InvocationBudgetState, 'NOT_RESERVED'>;
      reservationVersion: number;
      accountVersion: number;
    };
  }
    | {
    kind: 'CONTROL';
    payload: {
      receipt: {
        commandId: string;
        executionId: string;
        command: 'CANCEL' | 'PAUSE' | 'RESUME';
        outcome: 'ACCEPTED' | 'ALREADY_TERMINAL' | 'UNSUPPORTED';
        receivedAt: string;
      };
    };
  }
    | {
    kind: 'TERMINAL';
    payload: {
      state: TerminalInvocationState;
      result: ResultRef | null;
      error: ExecutionError | null;
    };
  }
    );

export interface InvocationEventsResponse {
  invocationId: string;
  /** 空数组仅表示本次没有新事件，不代表调用结束。 */
  events: ExecutionEvent[];
  nextCursor: { executionId: string; afterSequence: number };
  retainedAfterSequence: number;
}

export interface BudgetQuery {
  scope: AiScope;
  budgetRef: string;
}

/** 金额为十进制字符串，available 可以为负数；不要转为 JS number。 */
export interface BudgetAccountResponse {
  budgetRef: string;
  currency: string;
  limit: string;
  held: string;
  charged: string;
  available: string;
  rateVersion: DefinitionRef<'rate'>;
  version: number;
}
