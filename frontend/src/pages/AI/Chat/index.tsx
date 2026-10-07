import {MessageOutlined, SettingOutlined} from '@ant-design/icons';
import {useIntl} from '@umijs/max';
import {Alert, Card, Tag} from 'antd';
import React, {useMemo, useState} from 'react';
import chatMessages from '@/locales/zh-CN/aiChat';
import type {ChatModelOption} from '@/services/arte-ai';
import ChatConfigurationForm from './ChatConfigurationForm';
import ConversationWorkspace from './ConversationWorkspace';
import type {ConfigurationRejected} from './configurationInvalidation';
import {
  type ChatTestConfig,
  clearChatConfig,
  type ConfigStorageWarning,
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
  // 已保存的引用必须先重新发现并校验，之后才能用于新提交。
  const [config, setConfig] = useState<ChatTestConfig | null>(null);
  const [option, setOption] = useState<ChatModelOption | null>(null);
  const [warning, setWarning] = useState<ConfigStorageWarning | null>(
    restored.warning,
  );
  const [dirty, setDirty] = useState(false);
  const [configurationRejected, setConfigurationRejected] = useState(false);
  const [invalidationRevision, setInvalidationRevision] = useState(0);
  const scope = useMemo(
    () =>
      config
        ? {tenantId: config.tenantId, workspaceId: config.workspaceId}
        : null,
    [config?.tenantId, config?.workspaceId],
  );
  const activate = (value: ChatTestConfig, model: ChatModelOption) => {
    setConfig(value);
    setOption(model);
    setDirty(false);
    setConfigurationRejected(false);
  };
  const rejectConfiguration: ConfigurationRejected = (request, error) => {
    if (!config || request.scope.tenantId !== config.tenantId
      || request.scope.workspaceId !== config.workspaceId) return;
    // A retry may still refer to the previous model; its rejection must not invalidate a newer choice.
    const sameSelection = request.binding.id === config.bindingId
      && request.binding.version === config.bindingVersion
      && request.capability.id === config.capabilityId
      && request.capability.version === config.capabilityVersion
      && request.budgetRef === config.budgetRef;
    if (!sameSelection && error.httpStatus !== 401 && error.httpStatus !== 403) return;
    setDirty(true);
    setConfigurationRejected(true);
    setInvalidationRevision((revision) => revision + 1);
    setWarning(clearChatConfig() ? null : 'clearFailed');
  };

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
      <div
        className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[220px_minmax(0,1fr)] xl:grid-cols-[220px_minmax(0,1fr)_360px]">
        <ConversationWorkspace
          key={JSON.stringify(scope)}
          scope={scope}
          config={config}
          dirty={dirty}
          maxInputBytes={option?.limits.maxInputBytes}
          onConfigurationRejected={rejectConfiguration}
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
          {configurationRejected && (
            <Alert className="mb-4" type="warning" showIcon title={t('configurationRejected')}/>
          )}
          {config && !configurationRejected && (
            <div className="mb-4" aria-live="polite">
              <Alert
                type="success"
                showIcon
                title={t('configApplied')}
                description={t('backendUnverified')}
              />
              <p className="mb-0 text-xs">
                {option?.displayName} · {config.bindingId}@
                {config.bindingVersion} · {config.budgetRef}
              </p>
            </div>
          )}
          <ChatConfigurationForm
            restored={restored.config}
            invalidationRevision={invalidationRevision}
            t={t}
            translate={translate}
            onDirty={() => setDirty(true)}
            onRestore={activate}
            onApply={(value, model) => {
              activate(value, model);
              setWarning(saveChatConfig(value) ? null : 'saveFailed');
            }}
            onReset={() => {
              setConfig(null);
              setOption(null);
              setDirty(false);
              setConfigurationRejected(false);
              setWarning(clearChatConfig() ? null : 'clearFailed');
            }}
          />
        </Card>
      </div>
    </main>
  );
}
