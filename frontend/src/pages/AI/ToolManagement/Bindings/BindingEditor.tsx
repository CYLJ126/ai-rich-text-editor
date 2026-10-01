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
  ToolVersionPolicy,
  ToolVersionView,
} from '@/types/ai.tool.type';
import {PolicyOverrideFields} from '../components';

interface BindingFormValues {
  workspaceId?: string;
  toolId?: string;
  version?: string;
  versionPolicy: ToolVersionPolicy;
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
  const fixedReference = binding?.baselineTool || binding?.tool || resolved?.tool;
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
      version: binding?.baselineTool?.version || binding?.tool.version,
      versionPolicy: binding?.versionPolicy || 'follow-compatible',
      credentialReference: binding?.credentialReference,
      enabled: binding?.enabled ?? true,
      policyOverride: binding?.policyOverride,
    });
    setConfiguration(binding?.configuration || {});
    setConfigurationValid(true);
    setResolved(undefined);
    setVersions([]);
    if (binding) {
      setLoadingVersions(true);
      listToolVersions(binding.tool.namespace, binding.tool.name)
        .then(records => setVersions(records.filter(item => item.lifecycleState === 'published')))
        .catch(error => message.error(normalizeToolError(error).message))
        .finally(() => setLoadingVersions(false));
      return;
    }
    loadCatalog().then();
  }, [binding, defaultWorkspaceId, form, loadCatalog, open]);

  useEffect(
    () => () => {
      if (catalogSearchTimer.current) clearTimeout(catalogSearchTimer.current);
    },
    [],
  );

  const selectedToolId = Form.useWatch('toolId', form);
  const selectedVersion = Form.useWatch('version', form);
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
      setVersions(records.filter((item) => item.lifecycleState === 'published'));
      form.setFieldValue('version', tool.latestVersion);
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
      (fixedReference && values.version ? {...fixedReference, version: values.version} : undefined) ||
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
        versionPolicy: values.versionPolicy,
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
          ? `编辑配置：${fixedReference.namespace}.${fixedReference.name}`
          : '新建工具绑定'
      }
      width={760}
      open={open}
      destroyOnHidden
      onClose={onClose}
      extra={
        <Button onClick={() => form.submit()} type="primary" loading={saving}>
          保存配置
        </Button>
      }
    >
      <Alert
        type="info"
        showIcon
        message="只保存凭据引用，不保存敏感凭据"
        description="兼容升级保留你的配置、凭据和助手关联。选择新版本可在原绑定内升级；策略覆盖只能收紧服务端策略。"
        style={{marginBottom: 16}}
      />
      <Form
        form={form}
        layout="vertical"
        onFinish={save}
        initialValues={{enabled: true, versionPolicy: 'follow-compatible'}}
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
            <Descriptions.Item label="当前执行版本">
              {toolReferenceLabel(resolved?.tool || binding?.tool || fixedReference)}
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
          </Space>
        )}
        <Form.Item name="versionPolicy" label="升级方式" rules={[{required: true}]}>
          <Select options={[
            {value: 'follow-compatible', label: '自动跟随兼容升级（推荐）'},
            {value: 'pinned', label: '锁定所选版本'},
          ]}/>
        </Form.Item>
        <Form.Item name="version" label="基准版本" rules={[{required: true, message: '请选择版本'}]}
                   extra="自动升级以此版本为兼容基准。变更基准版本后仍保留当前配置，请查看升级说明。">
          <Select loading={loadingVersions} disabled={!fixedReference && !selectedTool}
                  options={versions.map(item => ({
                    value: item.reference.version,
                    label: `${item.reference.version}${item.compatibilityBaseVersion ? `（兼容 ${item.compatibilityBaseVersion}）` : ''}`
                  }))}/>
        </Form.Item>
        {versions.find(item => item.reference.version === selectedVersion)?.releaseNotes && (
          <Alert type="info" showIcon message="升级说明" style={{marginBottom: 16}}
                 description={versions.find(item => item.reference.version === selectedVersion)?.releaseNotes}/>
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
