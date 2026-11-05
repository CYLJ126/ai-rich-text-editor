import React, {useState} from 'react';
import {Button, Flex, Form, Input} from 'antd';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {tenantNameSchema} from '@/services/platform/tenant';

export default function TenantNameEditor({initialName, create, disabled, submitDisabled, onSubmit, onCancel}: {
  initialName: string;
  create?: boolean;
  disabled: boolean;
  submitDisabled?: boolean;
  onSubmit: (name: string) => Promise<boolean>;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initialName);
  const parsed = tenantNameSchema.safeParse(name);
  const dirty = name !== initialName;
  const submit = () => parsed.success && !disabled && !submitDisabled ? onSubmit(parsed.data) : Promise.resolve(false);
  useConfigurationDraft({
    group: 'tenant-info', label: create ? '新建组织租户草稿' : '租户名称草稿',
    status: dirty ? 'dirty' : 'clean',
    save: create ? undefined : submit,
    discard: () => {
      onCancel();
      return true;
    }
  });
  return <Form layout="vertical" onFinish={() => void submit()}>
    <Form.Item label={create ? '组织名称' : '租户名称'} required>
      <Input aria-label={create ? '组织名称' : '租户名称'} value={name} maxLength={100} disabled={disabled}
             onChange={event => setName(event.target.value)}/>
    </Form.Item>
    <Flex gap={8} wrap>
      <Button htmlType="submit" type="primary"
              disabled={disabled || submitDisabled || !parsed.success || !create && !dirty}>
        {create ? '创建组织租户' : '保存租户名称'}
      </Button>
      <Button disabled={disabled} onClick={onCancel}>取消</Button>
    </Flex>
  </Form>;
}
