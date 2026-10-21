import React, {useEffect, useState} from 'react';
import {Alert, Button, Drawer, Flex, Form, Input, InputNumber, Select, Switch, Typography,} from 'antd';
import {discoverToolChat, type ToolChatChoice,} from '@/services/arte-ai/toolChat';
import {listToolBindings, type ToolBinding} from '@/services/arte-ai/tool';
import {currentAiCredential} from '@/services/arte-ai/request';
import type {BrowserContext} from './storage';

/** 按需发现当前可见绑定；选择只表达意图，实际可用性与写入审批由受理系统检查。 */
export default function ToolChatSettings({
                                           context,
                                           value,
                                           onChange,
                                           disabled,
                                           currency,
                                         }: {
  context: BrowserContext;
  value: ToolChatChoice | null;
  onChange: (value: ToolChatChoice | null) => void;
  disabled: boolean;
  currency?: string;
}) {
  const [open, setOpen] = useState(false),
    [enabled, setEnabled] = useState(false),
    [error, setError] = useState('');
  const [bindings, setBindings] = useState<ToolBinding[]>([]),
    [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false),
    [loading, setLoading] = useState(false),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController(),
      credential = currentAiCredential();
    setLoading(true);
    setError('');
    void (async () => {
      try {
        const availability = await discoverToolChat(context.scope, {
          signal: controller.signal,
        });
        if (controller.signal.aborted || credential !== currentAiCredential())
          return;
        setEnabled(availability.body.data.enabled);
        if (!availability.body.data.enabled) return;
        const response = await listToolBindings(context.scope, cursor, {
          signal: controller.signal,
        });
        if (controller.signal.aborted || credential !== currentAiCredential())
          return;
        setBindings((previous) => [
          ...new Map(
            [...(cursor ? previous : []), ...response.body.data.records].map(
              (b) => [b.bindingId, b],
            ),
          ).values(),
        ]);
        setHasMore(
          !response.body.data.exhausted && !!response.body.data.nextCursor,
        );
        nextCursor.current = response.body.data.nextCursor;
      } catch {
        if (
          !controller.signal.aborted &&
          credential === currentAiCredential()
        ) {
          setEnabled(false);
          setError('工具聊天不可用或当前无读取资格，请联系管理员或重新检查。');
        }
      } finally {
        if (!controller.signal.aborted && credential === currentAiCredential())
          setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [
    open,
    cursor,
    revision,
    context.userId,
    context.scope.tenantId,
    context.scope.workspaceId,
  ]);
  const nextCursor = React.useRef<string | null>(null);
  const choice = value ?? {
    toolBindingIds: [],
    maxModelRounds: 8,
    maxToolCalls: 16,
    maxConcurrentTools: 2,
    budgetLimits: [{currency: currency ?? 'CNY', amount: '1.00'}],
  };
  const change = (patch: Partial<ToolChatChoice>) =>
    onChange({...choice, ...patch});
  return (
    <>
      <Button disabled={disabled} onClick={() => setOpen(true)}>
        {value ? `工具已开启 · ${value.toolBindingIds.length} 项` : '工具设置'}
      </Button>
      <Drawer title="本次聊天的工具" open={open} onClose={() => setOpen(false)}>
        <Flex vertical gap={12}>
          {error && <Alert type="warning" title={error}/>}
          {!enabled && !loading && !error && (
            <Alert type="info" title="此环境尚未开放工具聊天"/>
          )}
          <Button
            loading={loading}
            onClick={() => {
              setCursor(null);
              setRevision((n) => n + 1);
            }}
          >
            重新检查工具
          </Button>
          <Form layout="vertical">
            <Form.Item label="允许助手调用工具">
              <Switch
                aria-label="允许助手调用工具"
                checked={!!value}
                disabled={disabled || (!enabled && !value)}
                onChange={(checked) => onChange(checked ? choice : null)}
              />
            </Form.Item>
            {value && (
              <>
                <Form.Item label="允许使用的工具">
                  <Select
                    mode="multiple"
                    aria-label="允许使用的工具"
                    value={value.toolBindingIds}
                    disabled={disabled || !enabled}
                    optionFilterProp="label"
                    options={bindings
                      .filter(
                        (b) =>
                          b.enabled ||
                          value.toolBindingIds.includes(b.bindingId),
                      )
                      .map((b) => ({
                        value: b.bindingId,
                        label: b.name,
                        disabled: !b.enabled,
                      }))}
                    onChange={(toolBindingIds) => change({toolBindingIds})}
                  />
                </Form.Item>
                {hasMore && (
                  <Button
                    loading={loading}
                    onClick={() => setCursor(nextCursor.current)}
                  >
                    加载更多工具
                  </Button>
                )}
                <Form.Item
                  label={`本次总费用上限（${currency ?? '预算币种待读取'}）`}
                >
                  <Input
                    aria-label="工具聊天总费用上限"
                    disabled={disabled}
                    value={value.budgetLimits[0]?.amount ?? ''}
                    onChange={(event) =>
                      change({
                        budgetLimits: [
                          {
                            currency: currency ?? 'CNY',
                            amount: event.target.value,
                          },
                        ],
                      })
                    }
                  />
                </Form.Item>
                <Typography.Paragraph type="secondary">
                  这是本次模型与工具循环的共同上限，同时受原模型账户余额约束。当前本地工具免费；所有模型轮次计费。金额使用十进制字符串。
                </Typography.Paragraph>
                {value.readOnly && (
                  <Alert
                    type="info"
                    title="重新读取资料模式：服务端仅允许只读工具"
                  />
                )}
                <details>
                  <summary>高级限制</summary>
                  <Form.Item label="最多模型轮数">
                    <InputNumber
                      min={1}
                      max={32}
                      disabled={disabled}
                      value={value.maxModelRounds}
                      onChange={(n) => n && change({maxModelRounds: n})}
                    />
                  </Form.Item>
                  <Form.Item label="最多工具调用">
                    <InputNumber
                      min={1}
                      max={100}
                      disabled={disabled}
                      value={value.maxToolCalls}
                      onChange={(n) =>
                        n &&
                        change({
                          maxToolCalls: n,
                          maxConcurrentTools: Math.min(
                            n,
                            value.maxConcurrentTools,
                          ),
                        })
                      }
                    />
                  </Form.Item>
                  <Form.Item label="只读工具并发上限">
                    <InputNumber
                      min={1}
                      max={Math.min(16, value.maxToolCalls)}
                      disabled={disabled}
                      value={value.maxConcurrentTools}
                      onChange={(n) => n && change({maxConcurrentTools: n})}
                    />
                  </Form.Item>
                </details>
              </>
            )}
          </Form>
          <Typography.Paragraph>
            模型将自行决定是否调用所选工具。写操作仍须经过原有权限与人工控制。总期限沿用已应用的模型设置，人工等待也占用该期限。
          </Typography.Paragraph>
          <Button href="/AI/Tools">管理我的工具配置</Button>
        </Flex>
      </Drawer>
    </>
  );
}
