/**
 * AI 工具领域的前端契约。
 *
 * 字段名与 arte-ai 后端 record/DTO 保持一致。接口层禁止接收 ownerId，当前用户身份
 * 始终由服务端从登录上下文中解析，避免前端伪造调用归属。
 */

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue =
  | JsonPrimitive
  | JsonValue[]
  | { [key: string]: JsonValue };
export type JsonObject = Record<string, JsonValue>;

export const TOOL_PROVIDER_TYPES = [
  'local',
  'http',
  'openapi',
  'mcp',
  'agent',
] as const;
export type ToolProviderType = (typeof TOOL_PROVIDER_TYPES)[number];

export const TOOL_LIFECYCLE_STATES = [
  'draft',
  'published',
  'deprecated',
  'disabled',
] as const;
export type ToolLifecycleState = (typeof TOOL_LIFECYCLE_STATES)[number];

export const TOOL_EXECUTION_MODES = [
  'blocking',
  'non-blocking',
  'deferred',
] as const;
export type ToolExecutionMode = (typeof TOOL_EXECUTION_MODES)[number];

export const TOOL_RESULT_STATUSES = [
  'accepted',
  'succeeded',
  'failed',
  'denied',
  'requires-approval',
  'paused',
  'cancelled',
  'timed-out',
] as const;
export type ToolResultStatus = (typeof TOOL_RESULT_STATUSES)[number];

export const TOOL_TASK_STATUSES = [
  'QUEUED',
  'RUNNING',
  'WAITING_APPROVAL',
  'PAUSED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
] as const;
export type ToolTaskStatus = (typeof TOOL_TASK_STATUSES)[number];

export const TOOL_RISK_LEVELS = ['low', 'medium', 'high', 'critical'] as const;
export type ToolRiskLevel = (typeof TOOL_RISK_LEVELS)[number];

export type ToolErrorCategory =
  | 'VALIDATION'
  | 'AUTHENTICATION'
  | 'AUTHORIZATION'
  | 'POLICY'
  | 'APPROVAL'
  | 'RATE_LIMIT'
  | 'TIMEOUT'
  | 'CANCELLED'
  | 'CONFLICT'
  | 'DEPENDENCY'
  | 'BUSINESS'
  | 'INTERNAL';

export interface ToolApiEnvelope<T> {
  code?: string;
  desc?: string;
  success: boolean;
  data?: T;
  statistics?: Record<string, JsonPrimitive>;
}

export interface PageQuery {
  current?: number;
  pageSize?: number;
}

export interface PageResult<T> extends ToolApiEnvelope<never> {
  current: number;
  size: number;
  total: number;
  records: T[];
  pages?: number;
}

export interface ToolReference {
  namespace: string;
  name: string;
  version: string;
}

export interface ToolSchema {
  dialect: string;
  /** 后端以字符串保存 JSON Schema，使用前应通过 parseToolSchema 解析。 */
  schema: string;
}

export interface ToolCapabilities {
  supportsStreaming: boolean;
  executionModes: ToolExecutionMode[];
  supportsCancellation: boolean;
  supportsDryRun: boolean;
  inputModes: string[];
  outputModes: string[];
}

export interface ToolRiskProfile {
  level: ToolRiskLevel;
  readOnly: boolean;
  destructive: boolean;
  reversible: boolean;
  idempotent: boolean;
  openWorld: boolean;
  requiredScopes: string[];
  allowedNetworkTargets: string[];
}

/** Java Duration 的 ISO-8601 字符串，例如 PT30S、PT5M。 */
export type IsoDuration = string;

export interface ToolExecutionPolicy {
  executionMode: ToolExecutionMode;
  timeout: IsoDuration;
  maxRetries: number;
  retryBackoff: IsoDuration;
  maxOutputTokens: number;
  requiresApproval: boolean;
  allowsResultCache: boolean;
}

export interface ToolPolicyOverride {
  executionMode?: ToolExecutionMode;
  timeout?: IsoDuration;
  maxRetries?: number;
  retryBackoff?: IsoDuration;
  maxOutputTokens?: number;
  requiresApproval?: boolean;
  allowsResultCache?: boolean;
}

export interface ToolDefinition {
  reference: ToolReference;
  title: string;
  description: string;
  inputSchema: ToolSchema;
  outputSchema: ToolSchema;
  capabilities: ToolCapabilities;
  riskProfile: ToolRiskProfile;
  defaultConfiguration: JsonObject;
  defaultPolicy: ToolExecutionPolicy;
  tags: string[];
  deprecated: boolean;
}

export interface ToolQuery {
  namespace?: string;
  keyword?: string;
  tags?: string[];
  maximumRiskLevel?: ToolRiskLevel;
  includeDeprecated?: boolean;
}

export interface ToolVersionView {
  reference: ToolReference;
  toolId: string;
  title: string;
  description: string;
  lifecycleState: ToolLifecycleState;
  checksum: string;
  rowVersion: number;
  publishedAt?: string;
}

export interface ToolProviderSyncResult {
  providerId: string;
  succeeded: boolean;
  discoveredCount: number;
  createdCount: number;
  updatedCount: number;
  activatedCount: number;
  disabledCount: number;
  completedAt: string;
  errorMessage?: string;
}

export interface ToolBindingCommand {
  bindingId?: string;
  workspaceId?: string;
  tool: ToolReference;
  credentialReference?: string;
  configuration?: JsonObject;
  policyOverride?: ToolPolicyOverride;
  enabled?: boolean;
  expectedRowVersion?: number;
}

export interface ToolBindingView {
  bindingId: string;
  workspaceId?: string;
  tool: ToolReference;
  credentialReference?: string;
  configuration: JsonObject;
  policyOverride?: ToolPolicyOverride;
  enabled: boolean;
  available: boolean;
  rowVersion: number;
}

export interface ResolvedToolBinding {
  bindingId: string;
  ownerId: string;
  workspaceId?: string;
  providerId: string;
  toolId: string;
  tool: ToolReference;
  effectiveConfiguration: JsonObject;
  credentialReference?: string;
  effectivePolicy: ToolExecutionPolicy;
  enabled: boolean;
  rowVersion: number;
}

export interface AssistantToolCommand {
  bindingId: string;
  enabled: boolean;
  sortOrder: number;
  policyOverride?: ToolPolicyOverride;
}

export interface AssistantToolConfigurationCommand {
  workspaceId?: string;
  tools: AssistantToolCommand[];
}

/** Spring AI 暴露给模型的精简工具定义。 */
export interface ModelToolDefinition {
  name: string;
  description: string;
  inputSchema: string;
}

export interface RestToolInvokeRequest {
  tool: ToolReference;
  arguments?: JsonObject;
  bindingId?: string;
  workspaceId?: string;
  idempotencyKey?: string;
  executionMode?: ToolExecutionMode;
  timeout?: IsoDuration;
  maxRetries?: number;
}

export interface ToolTaskHandle {
  taskId: string;
  callId: string;
  tool: ToolReference;
  status: ToolTaskStatus;
  progress?: number;
  progressMessage?: string;
  resumeToken?: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  metadata: JsonObject;
}

export interface ToolUsage {
  startedAt: string;
  completedAt: string;
  duration: IsoDuration;
  inputTokens?: number;
  outputTokens?: number;
  cost?: number;
  currency?: string;
}

export interface ToolErrorPayload {
  code: string;
  category: ToolErrorCategory;
  message: string;
  retryable: boolean;
  details: JsonObject;
}

export interface ToolArtifact {
  id: string;
  name: string;
  mediaType?: string;
  uri: string;
  metadata: JsonObject;
}

export interface ToolContent {
  type: 'text' | 'structured' | 'image' | 'audio' | 'resource-link';
  text?: string;
  value?: JsonObject;
  mediaType?: string;
  uri?: string;
  metadata: JsonObject;
}

export type ToolResult<TOutput = JsonValue> =
  | {
  status: 'succeeded';
  output: TOutput;
  content: ToolContent[];
  artifacts: ToolArtifact[];
  usage: ToolUsage;
  metadata: JsonObject;
}
  | {
  status: 'accepted';
  taskHandle: ToolTaskHandle;
  metadata: JsonObject;
}
  | {
  status: 'requires-approval' | 'paused';
  approvalRequestId?: string;
  resumeToken: string;
  metadata: JsonObject;
}
  | {
  status: 'failed' | 'denied' | 'cancelled' | 'timed-out';
  error: ToolErrorPayload;
  usage?: ToolUsage;
  metadata: JsonObject;
};

export interface ToolResumeRequest {
  resumeToken: string;
}

export interface ToolApprovalRecord {
  id: number;
  requestId: string;
  callId: string;
  taskId: string;
  workflowRunId?: string;
  toolId: string;
  toolVersion: string;
  argumentsDigest: string;
  displayArguments: JsonObject;
  summary?: string;
  status: 'pending' | 'approved' | 'rejected' | 'expired';
  approverId?: string;
  decisionReason?: string;
  expiresAt: string;
  decidedAt?: string;
  createBy: string;
  createTime: string;
  updateBy: string;
  updateTime: string;
}

export interface ApprovalDecisionRequest {
  approved: boolean;
  reason?: string;
}

export interface ToolCallStatistics {
  total: number;
  succeeded: number;
  failed: number;
  denied: number;
  successRate: number;
  averageLatencyMs: number;
  inputTokens: number;
  outputTokens: number;
  retries: number;
  failureReasons: Record<string, number>;
}

export interface ToolPersistenceFields {
  id: number;
  createBy: string;
  createTime: string;
  updateBy: string;
  updateTime: string;
}

export interface ToolCallRecord extends ToolPersistenceFields {
  callId: string;
  traceId: string;
  spanId: string;
  ownerId: string;
  subjectId: string;
  convId?: string;
  messageId?: string;
  sourceType: string;
  sourceId?: string;
  toolId: string;
  toolVersion: string;
  bindingId?: string;
  executionMode: ToolExecutionMode;
  argumentsDigest: string;
  argumentsSnapshot: JsonObject;
  contextSnapshot: JsonObject;
  policySnapshot: JsonObject;
  status: ToolResultStatus;
  idempotencyKey?: string;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
  errorCode?: string;
  errorCategory?: string;
  errorMessage?: string;
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
  metadata: JsonObject;
  rowVersion: number;
}

export interface ToolCallResultRecord extends ToolPersistenceFields {
  resultId: string;
  callId: string;
  taskId?: string;
  status: ToolResultStatus;
  output?: JsonObject;
  content: JsonObject[];
  artifacts: JsonObject[];
  usageInfo?: JsonObject;
  errorInfo?: JsonObject;
  metadata: JsonObject;
  completedAt?: string;
}

export interface ToolCallDetail {
  call: ToolCallRecord;
  result?: ToolCallResultRecord;
  retryCount: number;
}

export type ToolExecutionEventType =
  | 'REQUESTED'
  | 'RESOLVED'
  | 'VALIDATED'
  | 'AUTHORIZED'
  | 'GUARDRAIL_EVALUATED'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_APPROVED'
  | 'APPROVAL_REJECTED'
  | 'APPROVAL_EXPIRED'
  | 'TASK_QUEUED'
  | 'STARTED'
  | 'TASK_PROGRESS_CHANGED'
  | 'RETRIED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DENIED'
  | 'PAUSED'
  | 'RESUMED'
  | 'CANCELLED'
  | 'TIMED_OUT';

export interface ToolExecutionEvent {
  eventId: string;
  type: ToolExecutionEventType;
  occurredAt: string;
  traceId: string;
  spanId?: string;
  callId: string;
  tool: ToolReference;
  attributes: JsonObject;
}

export interface ToolExecutionTrace {
  traceId: string;
  callId: string;
  events: ToolExecutionEvent[];
}
