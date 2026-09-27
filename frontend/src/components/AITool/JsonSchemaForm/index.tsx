import {Col, DatePicker, Form, Input, InputNumber, Row, Select, Switch, Typography,} from 'antd';
import type {Rule} from 'antd/es/form';
import dayjs from 'dayjs';
import React, {forwardRef, useEffect, useImperativeHandle, useMemo,} from 'react';
import {JsonEditor} from '@/components/JsonEditor';
import type {JsonObject, JsonValue} from '@/types/ai.tool.type';
import {optionSchemas, parseJsonSchema, resolveSchema, schemaDefaultValue, schemaType,} from './schema';
import type {JsonSchemaDefinition, JsonSchemaFormLocale, JsonSchemaFormProps, JsonSchemaFormRef,} from './types';

const DEFAULT_LOCALE: JsonSchemaFormLocale = {
  required: '此字段为必填项',
  invalidFormat: '格式不正确',
  invalidPattern: '内容不符合格式要求',
  selectPlaceholder: '请选择',
  inputPlaceholder: '请输入',
  jsonPlaceholder: '请输入合法 JSON',
  trueLabel: '是',
  falseLabel: '否',
};

type NamePath = Array<string | number>;

const JsonSchemaForm = forwardRef<JsonSchemaFormRef, JsonSchemaFormProps>(
  (
    {
      schema: schemaSource,
      value,
      initialValue,
      onChange,
      form: externalForm,
      disabled = false,
      columns = 2,
      locale: localeOverride,
      className,
      showDescriptions = true,
    },
    ref,
  ) => {
    const [internalForm] = Form.useForm<JsonObject>();
    const form = externalForm || internalForm;
    const schema = useMemo(() => parseJsonSchema(schemaSource), [schemaSource]);
    const locale = useMemo(
      () => ({...DEFAULT_LOCALE, ...localeOverride}),
      [localeOverride],
    );
    const defaults = useMemo(() => {
      const schemaDefaults = schemaDefaultValue(schema);
      return {
        ...(isJsonObject(schemaDefaults) ? schemaDefaults : {}),
        ...(initialValue || {}),
      };
    }, [initialValue, schema]);

    useEffect(() => {
      if (value) form.setFieldsValue(value);
    }, [form, value]);

    useImperativeHandle(
      ref,
      () => ({
        validate: () => form.validateFields(),
        getValue: () => form.getFieldsValue(true) as JsonObject,
        setValue: (nextValue) => form.setFieldsValue(nextValue),
        reset: () => form.resetFields(),
        form,
      }),
      [form],
    );

    const root = resolveSchema(schema, schema);
    const properties = root.properties || {};
    const rootRequired = new Set(root.required || []);

    return (
      <Form<JsonObject>
        form={form}
        className={className}
        layout="vertical"
        initialValues={defaults}
        disabled={disabled}
        onValuesChange={(_, values) => onChange?.(values)}
        preserve
      >
        {root.title && (
          <Typography.Title level={5}>{root.title}</Typography.Title>
        )}
        {showDescriptions && root.description && (
          <Typography.Paragraph type="secondary">
            {root.description}
          </Typography.Paragraph>
        )}
        <Row gutter={[16, 4]}>
          {Object.entries(properties).map(([name, property]) => (
            <SchemaField
              key={name}
              name={[name]}
              schema={property}
              rootSchema={schema}
              required={rootRequired.has(name)}
              disabled={disabled}
              columns={columns}
              locale={locale}
              showDescriptions={showDescriptions}
            />
          ))}
        </Row>
      </Form>
    );
  },
);

JsonSchemaForm.displayName = 'JsonSchemaForm';

interface SchemaFieldProps {
  name: NamePath;
  schema: JsonSchemaDefinition;
  rootSchema: JsonSchemaDefinition;
  required: boolean;
  disabled: boolean;
  columns: 1 | 2 | 3;
  locale: JsonSchemaFormLocale;
  showDescriptions: boolean;
}

function SchemaField(props: SchemaFieldProps) {
  const resolved = resolveSchema(props.schema, props.rootSchema);
  const type = schemaType(resolved);
  const span = props.columns === 1 ? 24 : props.columns === 2 ? 12 : 8;
  const fullWidth = type === 'object' || type === 'array';

  if (type === 'object' && resolved.properties) {
    const required = new Set(resolved.required || []);
    return (
      <Col xs={24} span={24}>
        <fieldset
          style={{
            border: '1px solid var(--ant-color-border-secondary)',
            borderRadius: 'var(--ant-border-radius-lg)',
            margin: '0 0 12px',
            padding: '12px 16px 4px',
          }}
        >
          <legend style={{padding: '0 6px', fontWeight: 600}}>
            {resolved.title || lastName(props.name)}
          </legend>
          {props.showDescriptions && resolved.description && (
            <Typography.Paragraph type="secondary">
              {resolved.description}
            </Typography.Paragraph>
          )}
          <Row gutter={[16, 4]}>
            {Object.entries(resolved.properties).map(([name, child]) => (
              <SchemaField
                {...props}
                key={name}
                name={[...props.name, name]}
                schema={child}
                required={required.has(name)}
              />
            ))}
          </Row>
        </fieldset>
      </Col>
    );
  }

  const rules = createRules(resolved, props.required, props.locale);
  const label = resolved.title || lastName(props.name);
  const extra = props.showDescriptions ? resolved.description : undefined;

  return (
    <Col xs={24} sm={fullWidth ? 24 : span} span={fullWidth ? 24 : span}>
      <Form.Item
        name={props.name}
        label={label}
        extra={extra}
        rules={rules}
        valuePropName={type === 'boolean' ? 'checked' : 'value'}
        getValueProps={
          resolved.format === 'date' || resolved.format === 'date-time'
            ? (fieldValue) => ({
              value: fieldValue ? dayjs(fieldValue as string) : undefined,
            })
            : undefined
        }
        normalize={
          resolved.format === 'date'
            ? (fieldValue) => fieldValue?.format('YYYY-MM-DD')
            : resolved.format === 'date-time'
              ? (fieldValue) => fieldValue?.toISOString()
              : undefined
        }
      >
        <SchemaInput
          schema={resolved}
          rootSchema={props.rootSchema}
          disabled={props.disabled || resolved.readOnly === true}
          locale={props.locale}
        />
      </Form.Item>
    </Col>
  );
}

function SchemaInput({
                       schema,
                       rootSchema,
                       disabled,
                       locale,
                     }: {
  schema: JsonSchemaDefinition;
  rootSchema: JsonSchemaDefinition;
  disabled: boolean;
  locale: JsonSchemaFormLocale;
}) {
  const type = schemaType(schema);
  const options = optionSchemas(schema);
  if (options) {
    return (
      <Select
        allowClear
        disabled={disabled}
        options={options}
        placeholder={locale.selectPlaceholder}
      />
    );
  }
  if (type === 'boolean') {
    return (
      <Switch
        disabled={disabled}
        checkedChildren={locale.trueLabel}
        unCheckedChildren={locale.falseLabel}
      />
    );
  }
  if (type === 'number' || type === 'integer') {
    return (
      <InputNumber
        disabled={disabled}
        min={schema.minimum}
        max={schema.maximum}
        step={schema.multipleOf || (type === 'integer' ? 1 : undefined)}
        precision={type === 'integer' ? 0 : undefined}
        placeholder={locale.inputPlaceholder}
        style={{width: '100%'}}
      />
    );
  }
  if (schema.format === 'date') {
    return <DatePicker disabled={disabled} style={{width: '100%'}}/>;
  }
  if (schema.format === 'date-time') {
    return (
      <DatePicker showTime disabled={disabled} style={{width: '100%'}}/>
    );
  }
  if (type === 'array' || type === 'object' || schema.oneOf || schema.anyOf) {
    return (
      <JsonEditor
        readOnly={disabled}
        height={180}
        placeholder={schemaDefaultValue(schema, rootSchema) || {}}
      />
    );
  }
  if (schema.format === 'password') {
    return (
      <Input.Password
        disabled={disabled}
        maxLength={schema.maxLength}
        placeholder={locale.inputPlaceholder}
      />
    );
  }
  if (schema.format === 'textarea' || (schema.maxLength || 0) > 200) {
    return (
      <Input.TextArea
        disabled={disabled}
        minLength={schema.minLength}
        maxLength={schema.maxLength}
        placeholder={locale.inputPlaceholder}
        autoSize={{minRows: 3, maxRows: 8}}
        showCount={Boolean(schema.maxLength)}
      />
    );
  }
  return (
    <Input
      disabled={disabled}
      minLength={schema.minLength}
      maxLength={schema.maxLength}
      placeholder={locale.inputPlaceholder}
    />
  );
}

function createRules(
  schema: JsonSchemaDefinition,
  required: boolean,
  locale: JsonSchemaFormLocale,
): Rule[] {
  const rules: Rule[] = [];
  if (required) rules.push({required: true, message: locale.required});
  if (schema.pattern) {
    try {
      rules.push({
        pattern: new RegExp(schema.pattern),
        message: locale.invalidPattern,
      });
    } catch {
      // 非法表达式应在工具定义发布时由后端拦截；前端不因坏 Schema 崩溃。
    }
  }
  if (schema.format === 'email') {
    rules.push({type: 'email', message: locale.invalidFormat});
  }
  if (schema.format === 'uri' || schema.format === 'url') {
    rules.push({type: 'url', message: locale.invalidFormat});
  }
  return rules;
}

function lastName(path: NamePath): string {
  return String(path.at(-1) || 'value');
}

function isJsonObject(value: JsonValue | undefined): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export default JsonSchemaForm;
export {JsonSchemaParseError, parseJsonSchema} from './schema';
export type {JsonSchemaFormProps, JsonSchemaFormRef} from './types';
