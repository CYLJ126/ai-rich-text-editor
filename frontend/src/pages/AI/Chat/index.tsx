import {MessageOutlined, SettingOutlined} from '@ant-design/icons';
import {useIntl} from '@umijs/max';
import {Alert, Button, Card, Form, Input, InputNumber, Tag} from 'antd';
import React, {useMemo, useState} from 'react';
import chatMessages from '@/locales/zh-CN/aiChat';
import ConversationWorkspace from './ConversationWorkspace';
import {
  chatConfigSchema,
  type ChatConfigValues,
  type ChatTestConfig,
  clearChatConfig,
  type ConfigStorageWarning,
  DEFAULT_CHAT_CONFIG,
  loadChatConfig,
  saveChatConfig,
} from './config';

export default function AiChatPage() {
  const intl = useIntl();
  const translate = (id: string) =>
    intl.formatMessage({
      id,
      defaultMessage: chatMessages[id as keyof typeof chatMessages],
    });
  const t = (key: string) => translate(`app.aiChat.${key}`);
  const [restored] = useState(loadChatConfig);
  const [config, setConfig] = useState<ChatTestConfig | null>(restored.config);
  const [warning, setWarning] = useState<ConfigStorageWarning | null>(
    restored.warning,
  );
  const [dirty, setDirty] = useState(false);
  const [form] = Form.useForm<ChatConfigValues>();
  const scope = useMemo(
    () =>
      config
        ? {tenantId: config.tenantId, workspaceId: config.workspaceId}
        : null,
    [config?.tenantId, config?.workspaceId],
  );

  const applyConfig = (values: ChatConfigValues) => {
    const parsed = chatConfigSchema.safeParse(values);
    if (!parsed.success) {
      form.setFields(
        parsed.error.issues.map((issue) => ({
          name: [issue.path[0] as keyof ChatConfigValues],
          errors: [translate(issue.message)],
        })),
      );
      return;
    }
    setConfig(parsed.data);
    form.setFieldsValue(parsed.data);
    setDirty(false);
    setWarning(saveChatConfig(parsed.data) ? null : 'saveFailed');
  };

  const resetConfig = () => {
    setConfig(null);
    setDirty(false);
    form.resetFields();
    form.setFieldsValue(DEFAULT_CHAT_CONFIG);
    setWarning(clearChatConfig() ? null : 'clearFailed');
  };

  const stringField = (name: keyof ChatConfigValues, placeholder?: string) => (
    <Form.Item key={name} name={name} label={t(`field.${name}`)} required>
      <Input maxLength={256} placeholder={placeholder}/>
    </Form.Item>
  );

  const numberField = (
    name: keyof ChatConfigValues,
    min: number,
    max: number,
    optional = false,
  ) => (
    <Form.Item
      key={name}
      name={name}
      label={t(`field.${name}`)}
      required={!optional}
      extra={optional ? t('samplingDefault') : `${min}–${max}`}
    >
      <InputNumber
        className="w-full"
        min={min}
        max={max}
        step={optional ? 0.1 : 1}
        placeholder={optional ? t('samplingDefault') : undefined}
      />
    </Form.Item>
  );

  return (
    <main
      className="flex min-h-full flex-col gap-4 p-4"
      aria-label={t('title')}
    >
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="m-0 text-xl font-semibold">
            <MessageOutlined/> {t('title')}
          </h1>
          <p className="mb-0 mt-2 text-[var(--ant-color-text-secondary)]">
            {t('subtitle')}
          </p>
        </div>
        <Tag color={config ? 'blue' : 'default'}>
          {t(
            dirty
              ? 'status.draft'
              : config
                ? 'status.applied'
                : 'status.missing',
          )}
        </Tag>
      </header>

      <Alert
        type="info"
        showIcon
        title={t('stageTitle')}
        description={t('stageDescription')}
      />

      <div
        className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[220px_minmax(0,1fr)] xl:grid-cols-[220px_minmax(0,1fr)_360px]">
        <ConversationWorkspace
          key={JSON.stringify(scope)}
          scope={scope}
          dirty={dirty}
          t={t}
        />

        <Card
          title={
            <>
              <SettingOutlined/> {t('configuration')}
            </>
          }
          size="small"
          className="min-w-0 lg:col-span-2 xl:col-span-1"
        >
          <p className="mt-0 text-[var(--ant-color-text-secondary)]">
            {t('configHint')}
          </p>
          {warning && (
            <div className="mb-4">
              <Alert type="warning" showIcon title={t(`storage.${warning}`)}/>
            </div>
          )}
          {config && (
            <div className="mb-4" aria-live="polite">
              <Alert
                type="success"
                showIcon
                title={t('configApplied')}
                description={t('backendUnverified')}
              />
              <dl className="mb-0 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
                <dt>{t('scope')}</dt>
                <dd className="m-0 break-all">
                  {config.tenantId} / {config.workspaceId}
                </dd>
                <dt>Capability</dt>
                <dd className="m-0 break-all">
                  {config.capabilityId}@{config.capabilityVersion}
                </dd>
                <dt>Binding</dt>
                <dd className="m-0 break-all">
                  {config.bindingId}@{config.bindingVersion}
                </dd>
                <dt>{t('field.budgetRef')}</dt>
                <dd className="m-0 break-all">{config.budgetRef}</dd>
              </dl>
            </div>
          )}
          <Form
            form={form}
            name="ai-new-chat-config"
            aria-label={t('configuration')}
            layout="vertical"
            initialValues={restored.config ?? DEFAULT_CHAT_CONFIG}
            onFinish={applyConfig}
            onValuesChange={(changed: Partial<ChatConfigValues>) => {
              setDirty(true);
              form.setFields(
                (Object.keys(changed) as (keyof ChatConfigValues)[]).map(
                  (name) => ({
                    name: [name],
                    errors: [],
                  }),
                ),
              );
            }}
          >
            <h2 className="text-sm font-semibold">{t('scope')}</h2>
            <div className="grid grid-cols-2 gap-x-3">
              {stringField('tenantId')}
              {stringField('workspaceId')}
            </div>
            <h2 className="text-sm font-semibold">
              {t('publishedReferences')}
            </h2>
            <div className="grid grid-cols-2 gap-x-3">
              {stringField('capabilityId')}
              {stringField('capabilityVersion', t('versionPlaceholder'))}
              {stringField('bindingId')}
              {stringField('bindingVersion', t('versionPlaceholder'))}
            </div>
            {stringField('budgetRef')}
            <h2 className="text-sm font-semibold">{t('limits')}</h2>
            <p className="text-xs text-[var(--ant-color-text-secondary)]">
              {t('limitsHint')}
            </p>
            <div className="grid grid-cols-2 gap-x-3">
              {numberField('maxInputTokens', 1, 10_000_000)}
              {numberField('maxOutputTokens', 1, 1_000_000)}
              {numberField('timeoutSeconds', 1, 3600)}
            </div>
            <h2 className="text-sm font-semibold">{t('sampling')}</h2>
            <div className="grid grid-cols-2 gap-x-3">
              {numberField('temperature', 0, 2, true)}
              {numberField('topP', 0, 1, true)}
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="primary" htmlType="submit">
                {t('apply')}
              </Button>
              <Button onClick={resetConfig}>{t('reset')}</Button>
            </div>
          </Form>
        </Card>
      </div>
    </main>
  );
}
