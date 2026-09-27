import type {JsonObject, JsonValue, ToolSchema} from '@/types/ai.tool.type';
import type {JsonSchemaDefinition, JsonSchemaType} from './types';

export class JsonSchemaParseError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'JsonSchemaParseError';
  }
}

export function parseJsonSchema(
  source: JsonSchemaDefinition | ToolSchema | string,
): JsonSchemaDefinition {
  if (typeof source === 'string') return parseSchemaText(source);
  if (isToolSchema(source)) return parseSchemaText(source.schema);
  if (!isRecord(source)) {
    throw new JsonSchemaParseError('JSON Schema 必须是对象');
  }
  return source;
}

export function resolveSchema(
  schema: JsonSchemaDefinition,
  root: JsonSchemaDefinition,
): JsonSchemaDefinition {
  const referenced = schema.$ref
    ? resolveLocalReference(schema.$ref, root)
    : {};
  const ownSchema = {...referenced, ...schema};
  delete ownSchema.$ref;

  if (!ownSchema.allOf?.length) return ownSchema;
  const merged = ownSchema.allOf.reduce<JsonSchemaDefinition>(
    (result, part) => mergeSchemas(result, resolveSchema(part, root)),
    {...ownSchema, allOf: undefined},
  );
  return merged;
}

export function schemaDefaultValue(
  schema: JsonSchemaDefinition,
  root: JsonSchemaDefinition = schema,
): JsonValue | undefined {
  const resolved = resolveSchema(schema, root);
  if (resolved.default !== undefined) return cloneJson(resolved.default);
  if (resolved.const !== undefined) return cloneJson(resolved.const);

  if (schemaType(resolved) === 'object' && resolved.properties) {
    const value: JsonObject = {};
    for (const [key, property] of Object.entries(resolved.properties)) {
      const propertyDefault = schemaDefaultValue(property, root);
      if (propertyDefault !== undefined) value[key] = propertyDefault;
    }
    return Object.keys(value).length > 0 ? value : undefined;
  }
  if (schemaType(resolved) === 'array' && resolved.default === undefined) {
    return undefined;
  }
  return undefined;
}

export function schemaType(schema: JsonSchemaDefinition): JsonSchemaType {
  const declared = Array.isArray(schema.type)
    ? schema.type.find((type) => type !== 'null')
    : schema.type;
  if (declared) return declared;
  if (schema.properties || schema.additionalProperties) return 'object';
  if (schema.items) return 'array';
  return 'string';
}

export function optionSchemas(
  schema: JsonSchemaDefinition,
): Array<{ label: string; value: JsonValue }> | undefined {
  if (schema.enum) {
    return schema.enum.map((value) => ({label: String(value), value}));
  }
  const variants = schema.oneOf || schema.anyOf;
  if (!variants?.length) return undefined;
  const options = variants
    .map((variant) => {
      const value = variant.const ?? variant.enum?.[0];
      if (value === undefined) return null;
      return {label: variant.title || String(value), value};
    })
    .filter(
      (item): item is { label: string; value: JsonValue } => item !== null,
    );
  return options.length === variants.length ? options : undefined;
}

function parseSchemaText(text: string): JsonSchemaDefinition {
  try {
    const parsed: unknown = JSON.parse(text);
    if (!isRecord(parsed)) {
      throw new JsonSchemaParseError('JSON Schema 必须是对象');
    }
    return parsed as JsonSchemaDefinition;
  } catch (error) {
    if (error instanceof JsonSchemaParseError) throw error;
    throw new JsonSchemaParseError('JSON Schema 不是合法 JSON', {
      cause: error,
    });
  }
}

function resolveLocalReference(
  reference: string,
  root: JsonSchemaDefinition,
): JsonSchemaDefinition {
  if (!reference.startsWith('#/')) {
    throw new JsonSchemaParseError(`暂不支持远程 Schema 引用：${reference}`);
  }
  const segments = reference
    .slice(2)
    .split('/')
    .map((segment) => segment.replaceAll('~1', '/').replaceAll('~0', '~'));
  let current: unknown = root;
  for (const segment of segments) {
    if (!isRecord(current) || !(segment in current)) {
      throw new JsonSchemaParseError(`无法解析 Schema 引用：${reference}`);
    }
    current = current[segment];
  }
  if (!isRecord(current)) {
    throw new JsonSchemaParseError(`Schema 引用不是对象：${reference}`);
  }
  return current as JsonSchemaDefinition;
}

function mergeSchemas(
  left: JsonSchemaDefinition,
  right: JsonSchemaDefinition,
): JsonSchemaDefinition {
  return {
    ...left,
    ...right,
    properties: {...left.properties, ...right.properties},
    required: [
      ...new Set([...(left.required || []), ...(right.required || [])]),
    ],
  };
}

function isToolSchema(value: unknown): value is ToolSchema {
  return (
    isRecord(value) &&
    typeof value.dialect === 'string' &&
    typeof value.schema === 'string'
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function cloneJson<T extends JsonValue>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
