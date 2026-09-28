import {Form, Input, InputNumber, Select} from 'antd';
import React from 'react';

export interface PolicyOverrideFieldsProps {
  name?: string | number;
  disabled?: boolean;
}

export default function PolicyOverrideFields({
                                               name,
                                               disabled,
                                             }: PolicyOverrideFieldsProps) {
  const field = (key: string) => (name === undefined ? key : [name, key]);
  return (
    <>
      <Form.Item
        name={field('timeout')}
        label="调用超时"
        tooltip="ISO-8601 Duration，例如 PT30S；只能短于或等于服务端策略"
      >
        <Input
          disabled={disabled}
          placeholder="继承，例如 PT30S"
          maxLength={40}
        />
      </Form.Item>
      <Form.Item name={field('maxRetries')} label="最大重试次数">
        <InputNumber
          disabled={disabled}
          min={0}
          precision={0}
          placeholder="继承"
          style={{width: '100%'}}
        />
      </Form.Item>
      <Form.Item
        name={field('retryBackoff')}
        label="重试退避"
        tooltip="ISO-8601 Duration，例如 PT1S；只能长于或等于服务端策略"
      >
        <Input
          disabled={disabled}
          placeholder="继承，例如 PT1S"
          maxLength={40}
        />
      </Form.Item>
      <Form.Item name={field('maxOutputTokens')} label="最大输出 Token">
        <InputNumber
          disabled={disabled}
          min={1}
          precision={0}
          placeholder="继承"
          style={{width: '100%'}}
        />
      </Form.Item>
      <Form.Item name={field('requiresApproval')} label="人工审批">
        <Select
          disabled={disabled}
          allowClear
          placeholder="继承服务端策略"
          options={[{label: '要求审批（收紧）', value: true}]}
        />
      </Form.Item>
      <Form.Item name={field('allowsResultCache')} label="结果缓存">
        <Select
          disabled={disabled}
          allowClear
          placeholder="继承服务端策略"
          options={[{label: '禁止缓存（收紧）', value: false}]}
        />
      </Form.Item>
    </>
  );
}
