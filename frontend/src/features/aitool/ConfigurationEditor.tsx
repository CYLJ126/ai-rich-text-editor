import React from 'react';
import {Alert, Collapse, Form, Input, InputNumber, Select, Typography,} from 'antd';
import {encodeJson, type JsonValue, parseConfiguration,} from '@/services/arte-ai/toolJson';
import type {ToolSchema} from '@/services/arte-ai/tool';

/** 常用标量字段直接填写；复杂配置展开原文编辑，后端始终独立校验完整 Schema。 */
export default function ConfigurationEditor({
                                              schema,
                                              value,
                                              onChange,
                                            }: {
  schema: ToolSchema;
  value: string;
  onChange: (json: string) => void;
}) {
  let tree: Extract<JsonValue, { kind: 'object' }> | null = null;
  let error: string | null = null;
  try {
    tree = parseConfiguration(value);
  } catch (failure) {
    error = (failure as Error).message;
  }
  const document = schema.document;
  const properties = (document.properties ?? {}) as Record<
    string,
    { type?: string; title?: string; description?: string; enum?: unknown[] }
  >;
  const required = Array.isArray(document.required)
    ? (document.required as string[])
    : [];
  const simple = Object.entries(properties).filter(([, field]) =>
    ['string', 'boolean', 'number', 'integer'].includes(field.type ?? ''),
  );
  const update = (name: string, field?: JsonValue) => {
    if (!tree) return;
    if (field) tree.fields.set(name, field);
    else tree.fields.delete(name);
    onChange(encodeJson(tree));
  };
  return (
    <>
      {simple.map(([name, field]) => {
        const current = tree?.fields.get(name);
        const common = {disabled: !tree, 'aria-label': field.title ?? name};
        return (
          <Form.Item
            key={name}
            label={`${field.title ?? name}${required.includes(name) ? '（必填）' : ''}`}
            extra={field.description}
          >
            {field.type === 'boolean' ? (
              <Select
                {...common}
                allowClear
                value={
                  current?.kind === 'boolean'
                    ? String(current.value)
                    : undefined
                }
                options={[
                  {value: 'true', label: '是'},
                  {value: 'false', label: '否'},
                ]}
                onChange={(next) =>
                  update(
                    name,
                    next === undefined
                      ? undefined
                      : {kind: 'boolean', value: next === 'true'},
                  )
                }
              />
            ) : field.type === 'string' &&
            field.enum?.every((item) => typeof item === 'string') ? (
              <Select
                {...common}
                allowClear
                value={current?.kind === 'string' ? current.value : undefined}
                options={field.enum.map((item) => ({
                  value: String(item),
                  label: String(item),
                }))}
                onChange={(next) =>
                  update(
                    name,
                    next === undefined
                      ? undefined
                      : {kind: 'string', value: next},
                  )
                }
              />
            ) : field.type === 'string' ? (
              <Input
                {...common}
                value={current?.kind === 'string' ? current.value : ''}
                onChange={(event) =>
                  update(
                    name,
                    event.target.value === '' && !required.includes(name)
                      ? undefined
                      : {kind: 'string', value: event.target.value},
                  )
                }
              />
            ) : (
              <InputNumber<string>
                {...common}
                stringMode
                value={current?.kind === 'number' ? current.raw : null}
                style={{width: '100%'}}
                onChange={(next) =>
                  update(
                    name,
                    next === null
                      ? undefined
                      : {kind: 'number', raw: String(next)},
                  )
                }
              />
            )}
          </Form.Item>
        );
      })}
      {!simple.length && (
        <Typography.Paragraph type="secondary">
          无须填写常用字段；如有嵌套或数组配置，可展开 JSON 编辑。
        </Typography.Paragraph>
      )}
      <Collapse
        items={[
          {
            key: 'json',
            label: '高级：完整配置 JSON',
            children: (
              <Input.TextArea
                aria-label="完整配置 JSON"
                value={value}
                rows={8}
                onChange={(event) => onChange(event.target.value)}
                spellCheck={false}
              />
            ),
          },
        ]}
      />
      {error && <Alert type="warning" title={error} showIcon/>}
    </>
  );
}
