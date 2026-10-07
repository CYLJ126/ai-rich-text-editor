export default {
  'app.aiChat.title': 'AI Chat Test',
  'app.aiChat.subtitle':
    'Configure the test environment for conversations, messages, history, and budgets.',
  'app.aiChat.status.missing': 'Configuration required',
  'app.aiChat.status.applied': 'Configuration applied',
  'app.aiChat.status.draft': 'Unapplied changes',
  'app.aiChat.stageTitle': 'Step 2: Page layout and test configuration',
  'app.aiChat.stageDescription':
    'Validate, apply, and save your configuration locally. Conversation and message actions will be connected in later steps.',
  'app.aiChat.conversations': 'Conversations',
  'app.aiChat.createConversation': 'New conversation',
  'app.aiChat.conversationsEmpty': 'Conversations are not connected yet',
  'app.aiChat.messages': 'Messages and history',
  'app.aiChat.noConversation': 'No conversation selected',
  'app.aiChat.messagesEmpty': 'Fill in and apply the test configuration first',
  'app.aiChat.messagesConfigured':
    'Configuration ready. Conversation management is the next step.',
  'app.aiChat.invocationState': 'Execution status',
  'app.aiChat.budgetAvailable': 'Budget available',
  'app.aiChat.notQueried': 'Not queried',
  'app.aiChat.messageInput': 'Message content',
  'app.aiChat.messagePlaceholder':
    'Create or select a conversation to send a message',
  'app.aiChat.send': 'Send message',
  'app.aiChat.configuration': 'Test configuration',
  'app.aiChat.configHint':
    'Enter published references available to your account. Requests reuse the existing login token and language.',
  'app.aiChat.configApplied': 'Configuration applied locally',
  'app.aiChat.backendUnverified':
    'References, permissions, and budget have not been verified by the backend yet.',
  'app.aiChat.scope': 'Tenant and workspace',
  'app.aiChat.publishedReferences': 'Published references with fixed versions',
  'app.aiChat.versionPlaceholder': 'For example v1; latest is not allowed',
  'app.aiChat.limits': 'Token and time limits',
  'app.aiChat.limitsHint':
    'The backend also checks the limits allowed by published definitions, models, and budgets.',
  'app.aiChat.sampling': 'Generation options (optional)',
  'app.aiChat.samplingDefault': 'Leave blank to use published defaults',
  'app.aiChat.apply': 'Apply configuration',
  'app.aiChat.reset': 'Clear configuration',
  'app.aiChat.field.tenantId': 'Tenant ID',
  'app.aiChat.field.workspaceId': 'Workspace ID',
  'app.aiChat.field.capabilityId': 'Capability ID',
  'app.aiChat.field.capabilityVersion': 'Capability version',
  'app.aiChat.field.bindingId': 'Binding ID',
  'app.aiChat.field.bindingVersion': 'Binding version',
  'app.aiChat.field.budgetRef': 'Budget reference',
  'app.aiChat.field.maxInputTokens': 'Max input tokens',
  'app.aiChat.field.maxOutputTokens': 'Max output tokens',
  'app.aiChat.field.timeoutSeconds': 'Timeout (seconds)',
  'app.aiChat.field.temperature': 'Temperature (0–2)',
  'app.aiChat.field.topP': 'Top P (greater than 0, up to 1)',
  'app.aiChat.validation.required':
    'This field is required and cannot contain only whitespace',
  'app.aiChat.validation.maxLength': 'At most 256 characters are allowed',
  'app.aiChat.validation.fixedVersion':
    'Enter a fixed published version; latest is not allowed',
  'app.aiChat.validation.number': 'Enter a valid number',
  'app.aiChat.validation.integer': 'Enter an integer',
  'app.aiChat.validation.positive': 'Must be at least 1',
  'app.aiChat.validation.range': 'Value is outside the allowed range',
  'app.aiChat.storage.invalid':
    'Saved configuration is invalid or incompatible. Please enter it again.',
  'app.aiChat.storage.unavailable':
    'Local storage is unavailable. Configuration will only apply to this page.',
  'app.aiChat.storage.saveFailed':
    'Applied on this page, but could not be saved. A refresh may lose the configuration.',
  'app.aiChat.storage.clearFailed':
    'Cleared on this page, but could not remove the saved configuration. A refresh may restore it.',
};
