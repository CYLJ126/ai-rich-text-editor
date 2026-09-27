import type {FormInstance} from 'antd';
import type {JsonObject, JsonValue, ToolSchema} from '@/types/ai.tool.type';

export type JsonSchemaType =
  | 'null'
  | 'boolean'
  | 'object'
  | 'array'
  | 'number'
  | 'integer'
  | 'string';

export interface JsonSchemaDefinition {
  $id?: string;
  $ref?: string;
  $defs?: Record<string, JsonSchemaDefinition>;
  definitions?: Record<string, JsonSchemaDefinition>;
  type?: JsonSchemaType | JsonSchemaType[];
  title?: string;
  description?: string;
  default?: JsonValue;
  examples?: JsonValue[];
  enum?: JsonValue[];
  const?: JsonValue;
  properties?: Record<string, JsonSchemaDefinition>;
  required?: string[];
  additionalProperties?: boolean | JsonSchemaDefinition;
  items?: JsonSchemaDefinition;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  format?: string;
  minimum?: number;
  maximum?: number;
  exclusiveMinimum?: number;
  exclusiveMaximum?: number;
  multipleOf?: number;
  readOnly?: boolean;
  writeOnly?: boolean;
  oneOf?: JsonSchemaDefinition[];
  anyOf?: JsonSchemaDefinition[];
  allOf?: JsonSchemaDefinition[];

  [key: string]: unknown;
}

export interface JsonSchemaFormLocale {
  required: string;
  invalidFormat: string;
  invalidPattern: string;
  selectPlaceholder: string;
  inputPlaceholder: string;
  jsonPlaceholder: string;
  trueLabel: string;
  falseLabel: string;
}

export interface JsonSchemaFormProps {
  schema: JsonSchemaDefinition | ToolSchema | string;
  value?: JsonObject;
  initialValue?: JsonObject;
  onChange?: (value: JsonObject) => void;
  form?: FormInstance<JsonObject>;
  disabled?: boolean;
  /** 每行字段数；窄屏会自动降为单列。 */
  columns?: 1 | 2 | 3;
  locale?: Partial<JsonSchemaFormLocale>;
  className?: string;
  showDescriptions?: boolean;
}

export interface JsonSchemaFormRef {
  validate: () => Promise<JsonObject>;
  getValue: () => JsonObject;
  setValue: (value: JsonObject) => void;
  reset: () => void;
  form: FormInstance<JsonObject>;
}
