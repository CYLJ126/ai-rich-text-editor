export interface WorkspaceSelection {
  tenantId: string;
  workspaceId: string;
}

export interface DefinitionRef {
  definitionType: string;
  definitionId: string;
  version: string;
}

export interface Conversation {
  conversationId: string;
  scope: WorkspaceSelection & {
    principal: { principalId: string; type: 'USER' | 'SERVICE' };
  };
  title: string;
  modelBindingRef: DefinitionRef;
  status: 'ACTIVE' | 'DELETED';
  version: number;
  resources: unknown[];
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
}

export interface ChatBootstrap {
  enabled: boolean;
  unavailableReason: 'CHAT_DISABLED' | 'NO_ACCESS' | null;
  workspaces: Array<WorkspaceSelection & { allowedActions: string[] }>;
  defaultModel: null | {
    name: string;
    providerId: string;
    bindingRef: DefinitionRef;
    destination: string;
    purpose: string;
    inputTypes: string[];
    streaming: boolean;
    retrievalEnabled?: boolean;
    contextMaxBytes: number;
    maxOutputTokens: number;
  };
}

export interface ExecutionError {
  code: string;
  failureStage: string;
  retryable: boolean;
  sideEffectStatus: 'NONE' | 'OCCURRED' | 'UNKNOWN';
  resultCertainty: 'CONFIRMED' | 'UNKNOWN';
}
