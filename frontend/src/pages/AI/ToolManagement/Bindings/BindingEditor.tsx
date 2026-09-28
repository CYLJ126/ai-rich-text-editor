import {
  Alert,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Form,
  Input,
  message,
  Select,
  Space,
  Switch,
  Tabs,
  Typography,
} from 'antd';
import React, {useCallback, useEffect, useMemo, useRef, useState,} from 'react';
import {JsonEditor} from '@/components';
import {
  compactPolicyOverride,
  containsSensitiveConfigurationKey,
  normalizeToolError,
  toolReferenceLabel,
} from '@/features/ai-tool';
import {listToolCatalog, listToolVersions, saveToolBinding,} from '@/services/ant-design-pro/ai.tool';
import type {
  JsonObject,
  ResolvedToolBinding,
  ToolBindingView,
  ToolCatalogItem,
  ToolPolicyOverride,
  ToolReference,
  ToolVersionView,
} from '@/types/ai.tool.type';
import {PolicyOverrideFields} from '../components';

interface BindingFormValues {
  workspaceId?: string;
  toolId?: string;
  version?: string;
  credentialReference?: string;
  enabled: boolean;
  policyOverride?: ToolPolicyOverride;
}

export interface BindingEditorProps {
  open: boolean;
  binding?: ToolBindingView;
  defaultWorkspaceId?: string;
  onClose: () => void;
  onSaved: () => void;
}

export default function BindingEditor({
                                        open,
                                        binding,
                                        defaultWorkspaceId,
                                        onClose,
                                        onSaved,
                                      }: BindingEditorProps) {
  const [form] = Form.useForm<BindingFormValues>();
  const [catalog, setCatalog] = useState<ToolCatalogItem[]>([]);
  const [versions, setVersions] = useState<ToolVersionView[]>([]);
  const [configuration, setConfiguration] = useState<JsonObject>({});
  const [configurationValid, setConfigurationValid] = useState(true);
  const [loadingCatalog, setLoadingCatalog] = useState(false);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resolved, setResolved] = useState<ResolvedToolBinding>();
  const catalogRequestSequence = useRef(0);
  const catalogSearchTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const fixedReference = binding?.tool || resolved?.tool;
  const fixedBindingId = binding?.bindingId || resolved?.bindingId;
  const expectedRowVersion = resolved?.rowVersion ?? binding?.rowVersion;

  const loadCatalog = useCallback(async (keyword?: string) => {
    const sequence = ++catalogRequestSequence.current;
    setLoadingCatalog(true);
    try {
      const page = await listToolCatalog({
        current: 1,
        pageSize: 100,
        lifecycleState: 'published',
        keyword: keyword?.trim() || undefined,
      });
      if (sequence === catalogRequestSequence.current) {
        setCatalog(page.records.filter((item) => item.latestVersion));
      }
    } catch (error) {
      if (sequence === catalogRequestSequence.current) {
        message.error(normalizeToolError(error).message).then();
      }
    } finally {
      if (sequence === catalogRequestSequence.current) setLoadingCatalog(false);
    }
  }, []);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({
      workspaceId: binding?.workspaceId || defaultWorkspaceId,
      credentialReference: binding?.credentialReference,
      enabled: binding?.enabled ?? true,
      policyOverride: binding?.policyOverride,
    });
    setConfiguration(binding?.configuration || {});
    setConfigurationValid(true);
    setResolved(undefined);
    if (binding) return;
    loadCatalog().then();
  }, [binding, defaultWorkspaceId, form, loadCatalog, open]);

  useEffect(
    () => () => {
      if (catalogSearchTimer.current) clearTimeout(catalogSearchTimer.current);
    },
    [],
  );

  const selectedToolId = Form.useWatch('toolId', form);
  const selectedTool = useMemo(
    () => catalog.find((item) => item.toolId === selectedToolId),
    [catalog, selectedToolId],
  );

  const loadVersions = async (toolId: string) => {
    const tool = catalog.find((item) => item.toolId === toolId);
    form.setFieldValue('version', undefined);
    setVersions([]);
    if (!tool) return;
    setLoadingVersions(true);
    try {
      const records = await listToolVersions(tool.namespace, tool.name);
      setVersions(
        records.filter((item) => item.lifecycleState === 'published'),
      );
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setLoadingVersions(false);
    }
  };

  const save = async (values: BindingFormValues) => {
    if (!configurationValid) {
      message.error('工具配置不是合法 JSON 对象').then();
      return;
    }
    if (containsSensitiveConfigurationKey(configuration)) {
      message.error('配置中不能包含密码、Token 或密钥，请改用凭据引用').then();
      return;
    }
    const tool: ToolReference | undefined =
      fixedReference ||
      (selectedTool && values.version
        ? {
          namespace: selectedTool.namespace,
          name: selectedTool.name,
          version: values.version,
        }
        : undefined);
    if (!tool) return;
    setSaving(true);
    try {
      const result = await saveToolBinding({
        bindingId: fixedBindingId,
        workspaceId: clean(values.workspaceId),
        tool,
        credentialReference: clean(values.credentialReference),
        configuration,
        policyOverride: compactPolicyOverride(values.policyOverride),
        enabled: values.enabled,
        expectedRowVersion,
      });
      setResolved(result);
      message.success('工具绑定已保存').then();
      onSaved();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={
        fixedReference
          ? `编辑绑定：${toolReferenceLabel(fixedReference)}`
          : '新建工具绑定'
      }
      width={760}
      open={open}
      destroyOnHidden
      onClose={onClose}
      extra={
        <Button onClick={() => form.submit()} type="primary" loading={saving}>
          保存并解析
        </Button>
      }
    >
      <Alert
        type="info"
        showIcon
        message="只保存凭据引用，不保存敏感凭据"
        description="策略覆盖只能收紧服务端策略。已保存绑定的作用域和固定工具版本不可修改，如需切换版本请新建绑定。"
        style={{marginBottom: 16}}
      />
      <Form
        form={form}
        layout="vertical"
        onFinish={save}
        initialValues={{enabled: true}}
      >
        <Form.Item
          name="workspaceId"
          label="工作空间 ID"
          tooltip="留空表示当前用户的个人作用域"
        >
          <Input
            disabled={Boolean(fixedBindingId)}
            allowClear
            maxLength={64}
            placeholder="个人作用域（留空）"
          />
        </Form.Item>
        {fixedReference ? (
          <Descriptions
            bordered
            size="small"
            column={1}
            style={{marginBottom: 16}}
          >
            <Descriptions.Item label="绑定 ID">
              <Typography.Text copyable>{fixedBindingId}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="固定工具版本">
              {toolReferenceLabel(fixedReference)}
            </Descriptions.Item>
            <Descriptions.Item label="乐观锁版本">
              {expectedRowVersion}
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <Space align="start" size={16} style={{display: 'flex'}}>
            <Form.Item
              name="toolId"
              label="已发布工具"
              rules={[{required: true, message: '请选择工具'}]}
              style={{flex: 1}}
            >
              <Select
                showSearch
                filterOption={false}
                loading={loadingCatalog}
                optionFilterProp="label"
                placeholder="选择工具"
                onChange={loadVersions}
                onSearch={(keyword) => {
                  if (catalogSearchTimer.current)
                    clearTimeout(catalogSearchTimer.current);
                  catalogSearchTimer.current = setTimeout(
                    () => loadCatalog(keyword),
                    300,
                  );
                }}
                options={catalog.map((item) => ({
                  value: item.toolId,
                  label: `${item.title}（${item.namespace}.${item.name}）`,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="version"
              label="固定版本"
              rules={[{required: true, message: '请选择版本'}]}
              style={{width: 220}}
            >
              <Select
                loading={loadingVersions}
                disabled={!selectedTool}
                placeholder="选择已发布版本"
                options={versions.map((item) => ({
                  value: item.reference.version,
                  label: item.reference.version,
                }))}
              />
            </Form.Item>
          </Space>
        )}
        <Form.Item
          name="credentialReference"
          label="凭据引用"
          tooltip="填写凭据存储系统中的引用 ID，不要填写 API Key、Token 或密码本身"
        >
          <Input
            allowClear
            maxLength={128}
            placeholder="例如 credential://personal/search-api"
          />
        </Form.Item>
        <Form.Item name="enabled" label="绑定状态" valuePropName="checked">
          <Switch checkedChildren="启用" unCheckedChildren="禁用"/>
        </Form.Item>
        <Divider titlePlacement="start">配置覆盖</Divider>
        <JsonEditor
          value={configuration}
          height={230}
          placeholder={{}}
          onValidate={setConfigurationValid}
          onChange={(value) => {
            if (value && !Array.isArray(value))
              setConfiguration(value as JsonObject);
            else if (value === null) setConfiguration({});
          }}
        />
        <Divider titlePlacement="start">策略覆盖</Divider>
        <PolicyOverrideFields name="policyOverride"/>
      </Form>

      {resolved && (
        <>
          <Divider titlePlacement="start">解析后的实际配置</Divider>
          <Tabs
            items={[
              {
                key: 'configuration',
                label: '有效配置',
                children: (
                  <JsonEditor
                    value={resolved.effectiveConfiguration}
                    readOnly
                    height={220}
                  />
                ),
              },
              {
                key: 'policy',
                label: '有效策略',
                children: (
                  <JsonEditor
                    value={resolved.effectivePolicy}
                    readOnly
                    height={220}
                  />
                ),
              },
            ]}
          />
        </>
      )}
    </Drawer>
  );
}

function clean(value?: string): string | undefined {
  const normalized = value?.trim();
  return normalized || undefined;
}
