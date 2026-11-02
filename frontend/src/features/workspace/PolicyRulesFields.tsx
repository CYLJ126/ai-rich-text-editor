import React from 'react';
import {Button, Checkbox, Collapse, Flex, Form, type FormInstance, Select, Switch, Typography} from 'antd';
import {type PolicyFormValues, resourceOptions, roleOptions, targetOptions} from './policyForm';

export default function PolicyRulesFields({form}: { form: FormInstance<PolicyFormValues> }) {
  const creationTypes = Form.useWatch('allowedCreationTypes', form) ?? [];
  const types = [...new Set([...resourceOptions.map(option => option.value), ...creationTypes])]
    .map(value => ({value, label: resourceOptions.find(option => option.value === value)?.label ?? value}));
  return <>
    <Form.Item name="personalCredentialsAllowed" label="允许个人凭据" valuePropName="checked"
               extra="租户和空间都允许时，个人模型才可以使用符合政策的连接。">
      <Switch aria-label="允许个人凭据"/>
    </Form.Item>

    <Form.Item name="allowedProtocols" label="允许的协议"
               extra="填写协议标识，例如 chat-completions-sse。空列表表示全部禁止。">
      <Select aria-label="允许的协议" mode="tags" tokenSeparators={[',', '，']}
              options={[{value: 'chat-completions-sse'}]}/>
    </Form.Item>

    <Form.Item name="allowedProviders" label="允许的供应商" extra="填写供应商标识，例如 deepseek。">
      <Select aria-label="允许的供应商" mode="tags" tokenSeparators={[',', '，']} options={[{value: 'deepseek'}]}/>
    </Form.Item>

    <Form.Item name="allowedDestinations" label="允许的目的地主机"
               extra="填写连接中的主机名，例如 api.deepseek.com；不包含 https://、端口或路径。">
      <Select aria-label="允许的目的地主机" mode="tags" tokenSeparators={[',', '，']}
              options={[{value: 'api.deepseek.com'}]}/>
    </Form.Item>

    <Form.Item name="guestAiAllowed" label="允许访客 AI" valuePropName="checked"
               extra="这项政策上限不代替模型与 AI 使用授权。">
      <Switch aria-label="允许访客 AI"/>
    </Form.Item>

    <Collapse items={[{
      key: 'advanced', label: '资源创建、导出与分享政策', forceRender: true, children: <>

        <Form.Item name="allowedCreationTypes" label="允许创建的资源类型">
          <Select aria-label="允许创建的资源类型" mode="tags" options={types}/>
        </Form.Item>
        <Typography.Paragraph>额外创建角色补充基础角色规则，只能关联已允许创建的资源类型。</Typography.Paragraph>

        <Form.List name="extraRoles">{(fields, {add, remove}) =>
          <Flex vertical gap={8}>
            {fields.map(field =>
              <Flex key={field.key} gap={8} wrap align="start">

                <Form.Item name={[field.name, 'resourceType']} label="资源类型" rules={[{required: true}]}>
                  <Select aria-label={`额外创建资源 ${field.name + 1}`} options={types} style={{minWidth: 150}}/>
                </Form.Item>

                <Form.Item name={[field.name, 'roles']} label="额外角色"><Checkbox.Group
                  options={roleOptions}/></Form.Item>

                <Button onClick={() => remove(field.name)}>移除此组角色</Button>
              </Flex>)}

            <Button onClick={() => add({resourceType: undefined, roles: []})}>添加额外创建角色</Button>
          </Flex>}
        </Form.List>

        <Form.Item name="exportTypes" label="允许导出的资源类型">
          <Select aria-label="允许导出的资源类型" mode="tags" options={types}/>
        </Form.Item>

        <Form.Item name="egressTypes" label="允许外发的资源类型">
          <Select aria-label="允许外发的资源类型" mode="tags" options={types}/>
        </Form.Item>

        <Form.Item name="shareTargetKinds" label="允许分享的目标类型">
          <Checkbox.Group options={targetOptions}/>
        </Form.Item>
      </>
    }]}/>
  </>;
}
