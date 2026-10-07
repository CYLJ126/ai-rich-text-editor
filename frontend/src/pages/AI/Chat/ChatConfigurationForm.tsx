import {Alert, Button, Collapse, Form, Input, InputNumber, Select,} from 'antd';
import React, {useEffect, useRef, useState} from 'react';
import {AiApiError, type ChatModelOption} from '@/services/arte-ai';
import {
  type ChatConfigValues,
  chatScopeSchema,
  type ChatTestConfig,
  DEFAULT_CHAT_CONFIG,
  matchesModel,
  modelOptionKey,
  selectedChatConfigSchema,
} from './config';
import {useConfigurationDiscovery, useSelectedBudget,} from './useConfigurationSelection';
import {hasAvailableBudget} from './useChatSession';

type Values = ChatConfigValues & { modelKey?: string };

export default function ChatConfigurationForm({
                                                restored,
                                                invalidationRevision = 0,
                                                onApply,
                                                onRestore,
                                                onDirty,
                                                onReset,
                                                t,
                                                translate,
                                              }: {
  restored: ChatTestConfig | null;
  invalidationRevision?: number;
  onApply: (config: ChatTestConfig, option: ChatModelOption) => void;
  onRestore: (config: ChatTestConfig, option: ChatModelOption) => void;
  onDirty: () => void;
  onReset: () => void;
  t: (key: string) => string;
  translate: (key: string) => string;
}) {
  const [form] = Form.useForm<Values>();
  const discovery = useConfigurationDiscovery();
  const selectedKey = Form.useWatch('modelKey', form);
  const budgetRef = Form.useWatch('budgetRef', form);
  const selected = discovery.options.find(
    (option) => modelOptionKey(option) === selectedKey,
  );
  const budget = useSelectedBudget(
    selected ? discovery.scope : null,
    budgetRef,
  );
  const [restoreInvalid, setRestoreInvalid] = useState(false);
  const edited = useRef(false);

  const markDirty = () => {
    edited.current = true;
    onDirty();
  };
  const fieldErrors = (issues: { path: PropertyKey[]; message: string }[]) => {
    form.setFields(
      issues.map((issue) => ({
        name: [
          ([
            'bindingId',
            'bindingVersion',
            'capabilityId',
            'capabilityVersion',
          ].includes(String(issue.path[0]))
            ? 'modelKey'
            : issue.path[0]) as keyof Values,
        ],
        errors: [translate(issue.message)],
      })),
    );
  };

  function selectModel(option: ChatModelOption | undefined, previous?: Values) {
    const sameModel = option && previous && matchesModel(previous, option);
    form.setFieldsValue({
      modelKey: option ? modelOptionKey(option) : undefined,
      bindingId: option?.binding.id ?? '',
      bindingVersion: option?.binding.version ?? '',
      capabilityId: option?.capability.id ?? '',
      capabilityVersion: option?.capability.version ?? '',
      budgetRef: option?.budgetRefs.includes(previous?.budgetRef ?? '')
        ? previous?.budgetRef
        : option?.budgetRefs.length === 1
          ? option.budgetRefs[0]
          : undefined,
      ...(option && !sameModel ? option.defaults : {}),
    });
    form.setFields(
      [
        'modelKey',
        'budgetRef',
        'maxInputTokens',
        'maxOutputTokens',
        'timeoutSeconds',
      ].map((name) => ({name: name as keyof Values, errors: []})),
    );
  }

  async function loadOptions(restore = false) {
    const values = form.getFieldsValue(true) as Values;
    const parsed = chatScopeSchema.safeParse(values);
    if (!parsed.success) {
      fieldErrors(parsed.error.issues);
      return;
    }
    onDirty();
    form.setFieldsValue(parsed.data);
    const options = await discovery.load(parsed.data);
    if (!options) return;
    const previous = options.find((option) => matchesModel(values, option));
    const option = previous ?? (options.length === 1 ? options[0] : undefined);
    selectModel(option, previous ? values : undefined);
    if (restore && restored && !edited.current) {
      const valid =
        previous && selectedChatConfigSchema(previous).safeParse(values);
      if (previous && valid?.success) {
        setRestoreInvalid(false);
        onRestore(valid.data, previous);
      } else {
        setRestoreInvalid(true);
      }
    }
  }

  useEffect(() => {
    if (restored) void loadOptions(true);
  }, []);

  useEffect(() => {
    if (!invalidationRevision) return;
    edited.current = true;
    discovery.clear();
    // Keep references and parameters for explicit reselection; never auto-apply a replacement.
    form.setFieldsValue({modelKey: undefined});
  }, [invalidationRevision]);

  function apply() {
    if (discovery.loading || !discovery.scope) return;
    const values = form.getFieldsValue(true) as Values;
    const chosen = discovery.options.find(
      (option) => modelOptionKey(option) === values.modelKey,
    );
    if (!chosen) return;
    if (
      values.tenantId.trim() !== discovery.scope.tenantId ||
      values.workspaceId.trim() !== discovery.scope.workspaceId
    )
      return;
    const parsed = selectedChatConfigSchema(chosen).safeParse(values);
    if (!parsed.success) {
      fieldErrors(parsed.error.issues);
      return;
    }
    setRestoreInvalid(false);
    onApply(parsed.data, chosen);
  }

  const errorAlert = (error: unknown) =>
    error ? (
      <Alert
        type="error"
        showIcon
        title={error instanceof AiApiError ? error.message : t('error.network')}
        description={
          error instanceof AiApiError
            ? error.httpStatus === 401
              ? t('error.login')
              : error.httpStatus === 403
                ? t('error.permission')
                : undefined
            : undefined
        }
      />
    ) : null;

  const numberField = (
    name: keyof Values,
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
        disabled={!selected || discovery.loading}
        placeholder={optional ? t('samplingDefault') : undefined}
      />
    </Form.Item>
  );

  return (
    <Form
      form={form}
      name="ai-new-chat-config"
      aria-label={t('configuration')}
      layout="vertical"
      initialValues={restored ?? DEFAULT_CHAT_CONFIG}
      onFinish={apply}
      onValuesChange={(changed: Partial<Values>) => {
        markDirty();
        form.setFields(
          Object.keys(changed).map((name) => ({
            name: name as keyof Values,
            errors: [],
          })),
        );
        if ('tenantId' in changed || 'workspaceId' in changed) {
          discovery.clear();
          selectModel(undefined);
          setRestoreInvalid(false);
        }
      }}
    >
      <h2 className="text-sm font-semibold">{t('scope')}</h2>
      <div className="grid grid-cols-2 gap-x-3">
        {(['tenantId', 'workspaceId'] as const).map((name) => (
          <Form.Item key={name} name={name} label={t(`field.${name}`)} required>
            <Input maxLength={256}/>
          </Form.Item>
        ))}
      </div>
      <Button
        className="mb-3"
        loading={discovery.loading}
        onClick={() => void loadOptions()}
      >
        {t('loadOptions')}
      </Button>
      {errorAlert(discovery.error)}
      {discovery.loading && <p role="status">{t('loadingOptions')}</p>}
      {discovery.scope &&
        !discovery.loading &&
        !discovery.error &&
        !discovery.options.length && (
          <Alert
            className="mb-3"
            type="info"
            showIcon
            title={t('optionsEmpty')}
          />
        )}
      {restoreInvalid && (
        <Alert
          className="mb-3"
          type="warning"
          showIcon
          title={t('savedSelectionUnavailable')}
        />
      )}
      <Form.Item name="modelKey" label={t('model')} required>
        <Select
          placeholder={t('selectModel')}
          loading={discovery.loading}
          disabled={!discovery.options.length || discovery.loading}
          options={discovery.options.map((option) => ({
            value: modelOptionKey(option),
            label: `${option.displayName} · ${option.binding.id}@${option.binding.version}`,
          }))}
          onChange={(key) => {
            markDirty();
            selectModel(
              discovery.options.find(
                (option) => modelOptionKey(option) === key,
              ),
              form.getFieldsValue(true),
            );
          }}
        />
      </Form.Item>
      {(
        [
          'bindingId',
          'bindingVersion',
          'capabilityId',
          'capabilityVersion',
        ] as const
      ).map((name) => (
        <Form.Item hidden key={name} name={name}>
          <Input/>
        </Form.Item>
      ))}
      <Form.Item name="budgetRef" label={t('field.budgetRef')} required>
        <Select
          placeholder={t('selectBudget')}
          disabled={!selected || discovery.loading}
          options={
            selected?.budgetRefs.map((value) => ({value, label: value})) ?? []
          }
        />
      </Form.Item>
      {selected && (
        <p className="text-xs text-[var(--ant-color-text-secondary)]">
          {t('contextWindow')}：{selected.contextWindowTokens} Token ·{' '}
          {t('inputByteLimit')}：{selected.limits.maxInputBytes} B
        </p>
      )}
      {budgetRef && selected && (
        <section
          className="mb-3 space-y-2"
          aria-label={t('selectedBudget')}
          aria-live="polite"
        >
          <Button
            size="small"
            loading={budget.loading}
            onClick={budget.refresh}
          >
            {t('refreshBudget')}
          </Button>
          {budget.loading && <p role="status">{t('loadingBudget')}</p>}
          {errorAlert(budget.error)}
          {budget.account && (
            <>
              <p className="m-0">
                {t('budgetAvailable')}：{budget.account.available}{' '}
                {budget.account.currency}
              </p>
              <p className="m-0 text-xs">
                {t('budgetLimit')}：{budget.account.limit} · {t('budgetHeld')}：
                {budget.account.held} · {t('budgetCharged')}：
                {budget.account.charged}
              </p>
              {!hasAvailableBudget(budget.account.available) && (
                <Alert type="warning" title={t('insufficientBudget')}/>
              )}
            </>
          )}
        </section>
      )}
      <Collapse
        className="mb-3"
        items={[
          {
            key: 'advanced',
            label: t('advancedParameters'),
            forceRender: true,
            children: (
              <>
                <p className="text-xs">{t('contextWindowHint')}</p>
                <Button
                  className="mb-3"
                  disabled={!selected || discovery.loading}
                  onClick={() => {
                    if (!selected) return;
                    form.setFieldsValue(selected.defaults);
                    form.setFields(
                      [
                        'maxInputTokens',
                        'maxOutputTokens',
                        'timeoutSeconds',
                      ].map((name) => ({
                        name: name as keyof Values,
                        errors: [],
                      })),
                    );
                    markDirty();
                  }}
                >
                  {t('useRecommendedLimits')}
                </Button>
                <div className="grid grid-cols-2 gap-x-3">
                  {numberField(
                    'maxInputTokens',
                    1,
                    selected?.limits.maxInputTokens ?? 10_000_000,
                  )}
                  {numberField(
                    'maxOutputTokens',
                    1,
                    selected?.limits.maxOutputTokens ?? 1_000_000,
                  )}
                  {numberField(
                    'timeoutSeconds',
                    1,
                    selected?.limits.maxTimeoutSeconds ?? 3600,
                  )}
                </div>
                <h2 className="text-sm font-semibold">{t('sampling')}</h2>
                <div className="grid grid-cols-2 gap-x-3">
                  {numberField('temperature', 0, 2, true)}
                  {numberField('topP', 0, 1, true)}
                </div>
              </>
            ),
          },
        ]}
      />
      <div className="flex flex-wrap gap-2">
        <Button
          type="primary"
          htmlType="submit"
          disabled={!selected || discovery.loading}
        >
          {t('apply')}
        </Button>
        <Button
          onClick={() => {
            edited.current = true;
            discovery.clear();
            form.resetFields();
            form.setFieldsValue({
              ...DEFAULT_CHAT_CONFIG,
              modelKey: undefined,
            });
            setRestoreInvalid(false);
            onReset();
          }}
        >
          {t('reset')}
        </Button>
      </div>
    </Form>
  );
}
