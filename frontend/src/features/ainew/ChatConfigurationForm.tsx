import {
  Alert,
  Button,
  Col,
  Collapse,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Spin,
  Typography
} from 'antd';
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
import {aiReadAccessIssue} from './accessFeedback';
import '../aiManagement.css';

export type ConfigurationDraft = ChatConfigValues & { modelKey?: string };
type Values = ChatConfigValues & { modelKey?: string };

export default function ChatConfigurationForm({
                                                restored,
                                                sharedDiscovery,
                                                invalidationRevision = 0,
                                                onApply,
                                                onRestore,
                                                onDirty,
                                                onReset,
                                                t,
                                                translate,
                                                fixedScope,
                                                onOptions,
                                                externalModel,
                                                applyRevision = 0,
                                                cancelRevision = 0,
                                                refreshRevision = 0,
                                                applied,
                                                onCancel,
                                                draftValues,
                                                initialDirty = false,
                                                onDraftChange,
                                              }: {
  restored: ChatTestConfig | null;
  sharedDiscovery?: ReturnType<typeof useConfigurationDiscovery>;
  invalidationRevision?: number;
  onApply: (config: ChatTestConfig, option: ChatModelOption) => void;
  onRestore: (config: ChatTestConfig, option: ChatModelOption) => void;
  onDirty: () => void;
  onReset: () => void;
  t: (key: string) => string;
  translate: (key: string) => string;
  fixedScope?: import('@/services/arte-ai').AiScope;
  onOptions?: (options: ChatModelOption[], error: unknown, loading: boolean) => void;
  externalModel?: { key: string; revision: number };
  applyRevision?: number;
  cancelRevision?: number;
  refreshRevision?: number;
  applied?: ChatTestConfig | null;
  onCancel?: () => void;
  draftValues?: ConfigurationDraft;
  initialDirty?: boolean;
  onDraftChange?: (values: ConfigurationDraft) => void;
}) {
  const [form] = Form.useForm<Values>();
  const localDiscovery = useConfigurationDiscovery();
  const discovery = sharedDiscovery ?? localDiscovery;
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
  const edited = useRef(initialDirty);

  const markDirty = () => {
    edited.current = true;
    onDirty();
    onDraftChange?.(form.getFieldsValue(true));
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

  async function loadOptions(restore = false, initialLoad = false, reuseDirectory = false) {
    const values = form.getFieldsValue(true) as Values;
    const parsed = chatScopeSchema.safeParse(values);
    if (!parsed.success) {
      fieldErrors(parsed.error.issues);
      return;
    }
    if (!initialLoad || !fixedScope) onDirty();
    form.setFieldsValue(parsed.data);
    const reusable = reuseDirectory && fixedScope && discovery.scope?.tenantId === parsed.data.tenantId &&
      discovery.scope?.workspaceId === parsed.data.workspaceId && !discovery.error && !discovery.loading;
    const options = reusable ? discovery.options : await discovery.load(parsed.data);
    if (!options) return;
    const previous = options.find((option) => matchesModel(values, option));
    const option = previous ?? (options.length === 1 ? options[0] : undefined);
    selectModel(option, previous ? values : undefined);
    if (fixedScope && !restored && !edited.current && option && option.budgetRefs.length === 1) {
      const valid = selectedChatConfigSchema(option).safeParse(form.getFieldsValue(true));
      if (valid.success) onRestore(valid.data, option);
    }
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
    if (restored || fixedScope) void loadOptions(!!restored, true, true);
  }, []);

  useEffect(() => {
    if (refreshRevision) void loadOptions(false, true);
  }, [refreshRevision]);

  useEffect(() => {
    if (!invalidationRevision) return;
    edited.current = true;
    discovery.clear();
    // Keep references and parameters for explicit reselection; never auto-apply a replacement.
    form.setFieldsValue({modelKey: undefined});
  }, [invalidationRevision]);

  useEffect(() => {
    onOptions?.(discovery.options, discovery.error, discovery.loading);
  }, [discovery.options, discovery.error, discovery.loading]);
  useEffect(() => {
    if (!externalModel) return;
    const option = discovery.options.find(option => modelOptionKey(option) === externalModel.key);
    if (option) {
      selectModel(option, form.getFieldsValue(true));
      markDirty();
    }
  }, [externalModel?.revision]);
  useEffect(() => {
    if (applyRevision) form.submit();
  }, [applyRevision]);
  useEffect(() => {
    if (!cancelRevision) return;
    form.setFieldsValue({
      ...DEFAULT_CHAT_CONFIG, ...fixedScope, ...applied,
      modelKey: applied ? modelOptionKey({
        binding: {
          type: 'binding',
          id: applied.bindingId,
          version: applied.bindingVersion
        }
      }) : undefined
    });
    edited.current = false;
  }, [cancelRevision]);

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

  const errorAlert = (error: unknown, source: 'configuration' | 'budget' = 'budget') => {
    if (!error) return null;
    const issue = aiReadAccessIssue(error);
    if (!issue && source === 'budget') return <Alert type="error" showIcon
                                                     title={error instanceof AiApiError ? error.message : t('error.network')}/>;
    const unavailable = error instanceof AiApiError && error.code === '205001';
    return <Alert
      type={issue || unavailable ? 'info' : 'error'}
      showIcon
      title={issue === 'login' ? '登录已失效'
        : issue === 'forbidden' ? source === 'configuration' ? '当前账号无法查看此空间的可用模型' : '当前账号暂无此预算的访问权限'
          : unavailable ? '此空间尚无可用 AI 模型或兼容预算。' : '可用模型暂时加载失败'}
      description={issue === 'login' ? t('error.login')
        : issue === 'forbidden' ? '请联系管理员确认使用权限，开通后重新加载。'
          : unavailable ? '请联系管理员确认当前空间的模型和预算配置。' : '请稍后重新加载，若仍无法读取，请联系管理员。'}
    />;
  };

  const numberField = (
    name: keyof Values,
    min: number,
    max: number,
    optional = false,
  ) => (
    <Col key={name} span={12}>
    <Form.Item
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
    </Col>
  );

  return (
    <Form
      form={form}
      name="ai-new-chat-config"
      className="ai-chat-configuration-form"
      aria-label={t('configuration')}
      layout="vertical"
      initialValues={{...DEFAULT_CHAT_CONFIG, ...fixedScope, ...restored, ...draftValues}}
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
      {!fixedScope && <Typography.Title level={2} className="ai-management-title">{t('scope')}</Typography.Title>}
      <Row gutter={12} hidden={!!fixedScope} style={{display: fixedScope ? 'none' : undefined}}>
        {(['tenantId', 'workspaceId'] as const).map((name) => (
          <Col key={name} span={12}>
            <Form.Item name={name} label={t(`field.${name}`)} required>
            <Input maxLength={256}/>
          </Form.Item>
          </Col>
        ))}
      </Row>
      <Button
        className="mb-3"
        loading={discovery.loading}
        onClick={() => void loadOptions()}
      >
        {t('loadOptions')}
      </Button>
      {errorAlert(discovery.error, 'configuration')}
      {discovery.loading && <Flex align="center" gap={8} role="status" className="ai-management-loading">
        <Spin size="small"/><Typography.Text>{t('loadingOptions')}</Typography.Text>
      </Flex>}
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
            label: `${option.displayName} · ${option.binding.id}@${option.binding.version}${option.toolCallsSupported ? ' · 支持工具调用' : ''}`,
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
        <Typography.Paragraph type="secondary" className="ai-management-muted">
          {t('contextWindow')}：{selected.contextWindowTokens} Token ·{' '}
          {t('inputByteLimit')}：{selected.limits.maxInputBytes} B
        </Typography.Paragraph>
      )}
      {budgetRef && selected && (
        <Flex component="section" vertical gap={8}
              className="ai-configuration-budget"
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
          {budget.loading && <Flex align="center" gap={8} role="status">
            <Spin size="small"/><Typography.Text>{t('loadingBudget')}</Typography.Text>
          </Flex>}
          {errorAlert(budget.error)}
          {budget.account && (
            <>
              <Descriptions column={1} size="small" className="ai-management-info" items={[
                {
                  key: 'available',
                  label: t('budgetAvailable'),
                  children: `${budget.account.available} ${budget.account.currency}`
                },
                {key: 'limit', label: t('budgetLimit'), children: budget.account.limit},
                {key: 'held', label: t('budgetHeld'), children: budget.account.held},
                {key: 'charged', label: t('budgetCharged'), children: budget.account.charged},
              ]}/>
              {!hasAvailableBudget(budget.account.available) && (
                <Alert type="warning" title={t('insufficientBudget')}/>
              )}
            </>
          )}
        </Flex>
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
                <Typography.Paragraph className="ai-management-muted">{t('contextWindowHint')}</Typography.Paragraph>
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
                <Row gutter={12}>
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
                </Row>
                <Typography.Title level={2} className="ai-management-title">{t('sampling')}</Typography.Title>
                <Row gutter={12}>
                  {numberField('temperature', 0, 2, true)}
                  {numberField('topP', 0, 1, true)}
                </Row>
              </>
            ),
          },
        ]}
      />
      <Flex gap={8} wrap>
        {onCancel && <Button onClick={onCancel}>取消修改</Button>}
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
      </Flex>
    </Form>
  );
}
