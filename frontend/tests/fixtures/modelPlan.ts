import type {ConnectionView} from '@/services/platform/connection';
import type {ManagedModelPlan, ModelPlanCommand} from '@/services/platform/modelPlan';

export const planCommand: ModelPlanCommand = {
  expectedVersion: 1, settings: {
    id: 'new-model',
    connection: {type: 'connection', id: 'reviewed', version: 'db-v1'},
    remoteModel: 'new-remote-model',
    contextWindowTokens: 8192,
    currency: 'CNY',
    inputPerMillion: '0.000000000000000001',
    outputPerMillion: '2.123456789012345678',
    usageReporting: false,
  }
};
export const managedPlan: ManagedModelPlan = {
  binding: {type: 'binding', id: 'reviewed-new-model', version: 'db-v2'},
  rate: {type: 'rate', id: 'reviewed-new-model', version: 'db-v2'}, ...planCommand.settings
};
export const planConnections: ConnectionView = {
  version: 1, initialized: true, allowedOrigins: ['https://api.deepseek.com'], connections: [{
    reference: planCommand.settings.connection,
    providerId: 'deepseek',
    protocol: {type: 'protocol', id: 'chat-completions-sse', version: 'v1'},
    endpoint: 'https://api.deepseek.com/v1',
    state: 'ENABLED',
    connectTimeoutSeconds: 5,
    responseTimeoutSeconds: 60,
    maxResponseBytes: 1048576,
  }]
};
