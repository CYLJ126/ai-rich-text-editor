import React, {useCallback, useEffect, useState} from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Select,
  Spin,
  Tag,
  Typography,
} from 'antd';
import type {AiScope} from '@/services/arte-ai';
import {
  bindingRequest,
  getTool,
  getToolBinding,
  getToolBindingHistory,
  previewToolUpgrade,
  type ToolBinding,
  type ToolBindingDetail,
  type ToolDetail,
  toolRefKey,
  type ToolSettings,
  type ToolSummary,
  type ToolUpgrade,
  type ToolWritePath,
} from '@/services/arte-ai/tool';
import {parseConfiguration} from '@/services/arte-ai/toolJson';
import ConfigurationEditor from './ConfigurationEditor';
import {toolFailure} from './useToolCommand';
import {useToolPage} from './useToolPage';

function BindingHistory({scope, id}: { scope: AiScope; id: string }) {
  const load = useCallback(
    (cursor: string | null, signal: AbortSignal) =>
      getToolBindingHistory(scope, id, cursor, {signal}),
    [scope, id],
  );
  const history = useToolPage(load);
  return (
    <Flex vertical gap={8}>
      {history.error && (
        <Alert
          type="warning"
          title={history.error}
          action={<Button onClick={history.refresh}>重新读取</Button>}
        />
      )}
      {history.records.map((item) => (
        <div key={item.receipt.commandId}>
          <Typography.Text>
            {item.receipt.recordedAt} · {item.action} ·{' '}
            {item.receipt.rejection === 'NONE'
              ? '已保存'
              : item.receipt.rejection}
          </Typography.Text>
          <br/>
          <Typography.Text type="secondary">{item.reason}</Typography.Text>
        </div>
      ))}
      <Button
        loading={history.loading}
        disabled={history.exhausted}
        onClick={history.more}
      >
        {history.exhausted ? '已显示全部历史' : '继续加载历史'}
      </Button>
    </Flex>
  );
}

export default function BindingEditor({
                                        scope,
                                        binding,
                                        tools,
                                        disabled,
                                        onWrite,
                                        onDirty,
                                      }: {
  scope: AiScope;
  binding: ToolBinding;
  tools: ToolSummary[];
  disabled: boolean;
  onWrite: (path: ToolWritePath, requestJson: string) => void;
  onDirty: (dirty: boolean) => void;
}) {
  const [detail, setDetail] = useState<ToolBindingDetail | null>(null);
  const [settings, setSettings] = useState<ToolSettings | null>(null);
  const [tool, setTool] = useState<ToolDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [preview, setPreview] = useState<ToolUpgrade | null>(null);
  const [accept, setAccept] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [history, setHistory] = useState(false);
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setDetail(null);
    setSettings(null);
    setPreview(null);
    setDirty(false);
    onDirty(false);
    getToolBinding(scope, binding.bindingId, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted) {
          setDetail(body.data);
          setSettings(body.data.revision.settings);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(toolFailure(failure));
      });
    return () => {
      controller.abort();
      onDirty(false);
    };
  }, [scope, binding.bindingId, revision, onDirty]);
  const targetKey = settings ? toolRefKey(settings.tool) : '';
  useEffect(() => {
    if (!settings) return;
    const controller = new AbortController();
    setTool(null);
    getTool(scope, settings.tool, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted) setTool(body.data);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(toolFailure(failure));
      });
    return () => controller.abort();
  }, [scope, targetKey]);
  useEffect(() => {
    setPreview(null);
    setAccept(false);
  }, [settings]);
  const [previewRequest, setPreviewRequest] = useState<ToolSettings | null>(
    null,
  );
  useEffect(() => {
    if (!previewRequest || !detail || previewRequest !== settings) return;
    const controller = new AbortController();
    setPreviewing(true);
    let request: string;
    try {
      request = bindingRequest(scope, {
        bindingId: binding.bindingId,
        expectedVersion: detail.binding.version,
        settings: previewRequest,
      });
    } catch (failure) {
      setError((failure as Error).message);
      setPreviewing(false);
      return;
    }
    previewToolUpgrade(request, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted) setPreview(body.data);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(toolFailure(failure));
      })
      .finally(() => {
        if (!controller.signal.aborted) setPreviewing(false);
      });
    return () => {
      controller.abort();
      setPreviewing(false);
    };
  }, [previewRequest, settings, detail, scope, binding.bindingId]);
  const change = (patch: Partial<ToolSettings>) => {
    if (!settings) return;
    setSettings({...settings, ...patch});
    setDirty(true);
    onDirty(true);
    setPreviewRequest(null);
  };
  const upgrading =
    settings &&
    detail &&
    toolRefKey(settings.tool) !== toolRefKey(detail.revision.settings.tool);
  const save = () => {
    if (!settings || !detail) return;
    try {
      parseConfiguration(settings.configurationJson);
      const request = bindingRequest(scope, {
        bindingId: binding.bindingId,
        expectedVersion: detail.binding.version,
        reason: upgrading ? '本人确认工具配置升级' : '本人更新工具配置',
        settings,
        ...(upgrading
          ? {
            expectedPreviewDigest: preview?.digest ?? undefined,
            acceptIncompatible: accept,
          }
          : {}),
      });
      onWrite(upgrading ? 'upgradeBinding' : 'saveBinding', request);
    } catch (failure) {
      setError((failure as Error).message);
    }
  };
  return (
    <Flex vertical gap={16}>
      <Flex justify="space-between" gap={8} wrap>
        <Typography.Title level={4}>{binding.name}</Typography.Title>
        <Button
          onClick={() => {
            if (
              !dirty ||
              window.confirm('重新读取将放弃当前未保存输入，是否继续？')
            ) {
              setError(null);
              setRevision((value) => value + 1);
            }
          }}
        >
          重新读取
        </Button>
      </Flex>
      {dirty && (
        <Typography.Text type="secondary">
          输入尚未保存。关闭或切换空间前会提示确认；版本冲突时请先保留需要的输入，再重新读取。
        </Typography.Text>
      )}
      {error && <Alert type="warning" title={error}/>}
      {!settings && !error && <Spin/>}
      {detail && settings && (
        <>
          <Tag>
            {binding.bindingId.startsWith('default-tool-')
              ? '默认配置'
              : '命名配置'}{' '}
            · {detail.binding.enabled ? '已启用' : '已停用'}
          </Tag>
          <Form layout="vertical" disabled={disabled}>
            <Form.Item label="配置名称">
              <Input
                aria-label="配置名称"
                maxLength={200}
                value={settings.name}
                onChange={(event) => change({name: event.target.value})}
              />
            </Form.Item>
            {tool ? (
              <ConfigurationEditor
                schema={tool.configurationSchema}
                value={settings.configurationJson}
                onChange={(configurationJson) => change({configurationJson})}
              />
            ) : (
              <Typography.Text type="secondary">
                正在读取工具配置要求；已有配置内容仍保留。
              </Typography.Text>
            )}
            <Collapse
              className="tool-advanced"
              items={[
                {
                  key: 'advanced',
                  label: '高级：版本与限制',
                  children: (
                    <Flex vertical gap={12}>
                      <Form.Item label="版本策略">
                        <Select
                          aria-label="版本策略"
                          value={settings.versionPolicy}
                          onChange={(versionPolicy) =>
                            change({versionPolicy})
                          }
                          options={[
                            {value: 'PINNED', label: '固定当前版本'},
                            {
                              value: 'FOLLOW_COMPATIBLE',
                              label: '沿审核兼容链跟随',
                            },
                          ]}
                        />
                      </Form.Item>
                      <Form.Item label="基准工具版本（改变需预览并确认升级）">
                        <Select
                          aria-label="基准工具版本"
                          value={targetKey}
                          onChange={(key) => {
                            const selected = tools.find(
                              (item) => toolRefKey(item.tool) === key,
                            );
                            if (selected) change({tool: selected.tool});
                          }}
                          options={[
                            detail.revision.settings.tool,
                            ...tools
                              .filter(
                                (item) =>
                                  item.tool.id === binding.toolId &&
                                  item.release.permitsNewBinding,
                              )
                              .map((item) => item.tool),
                          ]
                            .filter(
                              (item, index, all) =>
                                all.findIndex(
                                  (other) =>
                                    toolRefKey(other) === toolRefKey(item),
                                ) === index,
                            )
                            .map((ref) => ({
                              value: toolRefKey(ref),
                              label: `${ref.id} / ${ref.version}`,
                            }))}
                        />
                      </Form.Item>
                      <Checkbox
                        checked={settings.policy !== null}
                        disabled={!tool}
                        onChange={(event) =>
                          change({
                            policy:
                              event.target.checked && tool
                                ? {...tool.tool.defaultPolicy}
                                : null,
                          })
                        }
                      >
                        额外收紧执行限制
                      </Checkbox>
                      {settings.policy &&
                        (
                          [
                            'timeoutMillis',
                            'maxAttempts',
                            'retryBackoffMillis',
                            'maxInputBytes',
                            'maxOutputBytes',
                          ] as const
                        ).map((field) => (
                          <Form.Item
                            key={field}
                            label={
                              {
                                timeoutMillis: '总期限（毫秒）',
                                maxAttempts: '最多尝试次数',
                                retryBackoffMillis: '最小退避（毫秒）',
                                maxInputBytes: '输入字节上限',
                                maxOutputBytes: '输出字节上限',
                              }[field]
                            }
                          >
                            <InputNumber
                              aria-label={field}
                              min={field === 'retryBackoffMillis' ? 0 : 1}
                              precision={0}
                              value={settings.policy?.[field]}
                              onChange={(value) => {
                                if (value !== null && settings.policy)
                                  change({
                                    policy: {
                                      ...settings.policy,
                                      [field]: value,
                                    },
                                  });
                              }}
                            />
                          </Form.Item>
                        ))}
                      <Typography.Text type="secondary">
                        限制取工具、服务器与本人配置的交集；不扩大权限。当前页面不接入远端凭据。
                      </Typography.Text>
                      <Descriptions
                        column={1}
                        size="small"
                        items={[
                          {
                            key: 'version',
                            label: '当前修订',
                            children: `${detail.revision.revision.version}（治理版本 ${detail.binding.version}）`,
                          },
                          {
                            key: 'tool',
                            label: '工具基准',
                            children: `${detail.revision.settings.tool.id} / ${detail.revision.settings.tool.version}`,
                          },
                        ]}
                      />
                    </Flex>
                  ),
                },
              ]}
            />
          </Form>
          <Flex gap={8} wrap>
            <Button
              loading={previewing}
              disabled={disabled || !tool}
              onClick={() => {
                setError(null);
                const request = {...settings};
                setSettings(request);
                setPreviewRequest(request);
              }}
            >
              校验并预览有效配置
            </Button>
            <Button
              type="primary"
              disabled={
                disabled ||
                !dirty ||
                !settings.name.trim() ||
                !tool ||
                (!!upgrading &&
                  (!preview?.valid ||
                    !preview.digest ||
                    (!preview.compatible && !accept)))
              }
              onClick={save}
            >
              {upgrading ? '确认升级' : '保存配置'}
            </Button>
            <Button
              danger={detail.binding.enabled}
              disabled={disabled || dirty}
              onClick={() =>
                onWrite(
                  'setBindingState',
                  JSON.stringify({
                    scope,
                    bindingId: binding.bindingId,
                    expectedVersion: detail.binding.version,
                    reason: detail.binding.enabled
                      ? '本人停用工具配置'
                      : '本人启用工具配置',
                    enabled: !detail.binding.enabled,
                  }),
                )
              }
            >
              {detail.binding.enabled ? '停用配置' : '启用配置'}
            </Button>
          </Flex>
          {preview && (
            <Alert
              type={preview.valid ? 'info' : 'warning'}
              title={
                preview.valid
                  ? '配置校验通过；预览不会执行工具或授权调用。'
                  : `配置暂不可用：${preview.rejection}`
              }
              description={
                <Flex vertical gap={8}>
                  <Typography.Text>
                    版本变化：{preview.currentBaseline.version} →{' '}
                    {settings.tool.version}；
                    {preview.compatible ? '存在审核兼容路径' : '无审核兼容路径'}
                  </Typography.Text>
                  <Typography.Text>
                    变化项：{preview.changes.join('、') || '无'}
                  </Typography.Text>
                  {preview.proposed && (
                    <Typography.Text>
                      有效限制：{preview.proposed.policy.timeoutMillis / 1000}
                      s，{preview.proposed.policy.maxAttempts} 次尝试，输入{' '}
                      {preview.proposed.policy.maxInputBytes} 字节，输出{' '}
                      {preview.proposed.policy.maxOutputBytes} 字节
                    </Typography.Text>
                  )}
                  {upgrading && preview.valid && !preview.compatible && (
                    <Checkbox
                      checked={accept}
                      onChange={(event) => setAccept(event.target.checked)}
                    >
                      我已核对配置及风险，确认此非兼容升级
                    </Checkbox>
                  )}
                </Flex>
              }
            />
          )}
          <Button type="link" onClick={() => setHistory((value) => !value)}>
            {history ? '收起变更历史' : '查看变更历史'}
          </Button>
          {history && <BindingHistory scope={scope} id={binding.bindingId}/>}
        </>
      )}
    </Flex>
  );
}
