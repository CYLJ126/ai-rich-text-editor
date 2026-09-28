import type {
  AssistantToolCommand,
  JsonObject,
  ToolBindingView,
  ToolPolicyOverride,
  ToolReference,
} from '@/types/ai.tool.type';

const SENSITIVE_KEY =
  /(password|secret|token|api[-_]?key|private[-_]?key|credential)/i;

export function toolReferenceKey(reference: ToolReference): string {
  return `${reference.namespace}\u0000${reference.name}\u0000${reference.version}`;
}

export function toolReferenceLabel(reference: ToolReference): string {
  return `${reference.namespace}.${reference.name}@${reference.version}`;
}

export function containsSensitiveConfigurationKey(value: unknown): boolean {
  if (Array.isArray(value)) {
    return value.some(containsSensitiveConfigurationKey);
  }
  if (!isObject(value)) return false;
  return Object.entries(value).some(
    ([key, nested]) =>
      SENSITIVE_KEY.test(key) || containsSensitiveConfigurationKey(nested),
  );
}

export function compactPolicyOverride(
  value?: ToolPolicyOverride,
): ToolPolicyOverride | undefined {
  if (!value) return undefined;
  const result = Object.fromEntries(
    Object.entries(value).filter(
      ([, field]) => field !== undefined && field !== null && field !== '',
    ),
  ) as ToolPolicyOverride;
  return Object.keys(result).length ? result : undefined;
}

export function validateAssistantToolCommands(
  commands: AssistantToolCommand[],
  bindings: ToolBindingView[],
): string[] {
  const errors: string[] = [];
  const bindingById = new Map(
    bindings.map((binding) => [binding.bindingId, binding]),
  );
  const selectedBindings = new Set<string>();
  const enabledTools = new Set<string>();

  for (const command of commands) {
    if (selectedBindings.has(command.bindingId)) {
      errors.push(`重复绑定：${command.bindingId}`);
      continue;
    }
    selectedBindings.add(command.bindingId);
    const binding = bindingById.get(command.bindingId);
    if (!binding) {
      errors.push(`绑定不存在或不属于当前作用域：${command.bindingId}`);
      continue;
    }
    if (!binding.enabled || !binding.available) {
      errors.push(`绑定不可用：${toolReferenceLabel(binding.tool)}`);
    }
    const modelName = `${binding.tool.namespace}__${binding.tool.name}`;
    if (command.enabled && enabledTools.has(modelName)) {
      errors.push(
        `同一助手不能启用工具的多个版本：${binding.tool.namespace}.${binding.tool.name}`,
      );
    }
    if (command.enabled) enabledTools.add(modelName);
  }
  return errors;
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
