import React, {useEffect, useState} from 'react';
import {Alert, Button, Select, Typography} from 'antd';
import {availableToolBudgets, type ToolBudgetOptions, type ToolPrice,} from '@/services/arte-ai/toolInvocation';
import {currentAiCredential} from '@/services/arte-ai/request';
import type {AiScope} from '@/services/arte-ai/types';

/** 只读取当前可用账户；用户明确选择，服务端预览和派发仍重新授权。 */
export default function ToolBudgetPicker({
                                           scope,
                                           bindingId,
                                           value,
                                           onChange,
                                           disabled,
                                         }: {
  scope: AiScope;
  bindingId: string;
  value?: string;
  onChange: (budgetRef: string | undefined, price: ToolPrice | null) => void;
  disabled?: boolean;
}) {
  const [options, setOptions] = useState<ToolBudgetOptions | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController(),
      credential = currentAiCredential();
    setOptions(null);
    setError('');
    void availableToolBudgets(scope, bindingId, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted && currentAiCredential() === credential)
          setOptions(body.data);
      })
      .catch(() => {
        if (!controller.signal.aborted && currentAiCredential() === credential)
          setError('暂时无法读取工具报价与账户，请重新打开设置后重试。');
      });
    return () => controller.abort();
  }, [scope.tenantId, scope.workspaceId, bindingId, revision]);
  if (error)
    return (
      <Alert
        type="warning"
        title={error}
        action={
          <Button onClick={() => setRevision((v) => v + 1)} disabled={disabled}>
            重试
          </Button>
        }
      />
    );
  if (!options)
    return (
      <Typography.Text type="secondary">正在读取工具费用…</Typography.Text>
    );
  if (!options.price)
    return (
      <Typography.Text type="secondary">
        本工具免费，无需费用账户。
      </Typography.Text>
    );
  return (
    <div>
      <Typography.Paragraph>
        按次报价上界：{options.price.perCall.amount}{' '}
        {options.price.perCall.currency}
        ；实际费用以可信账单结算，未知费用保留预留。
      </Typography.Paragraph>
      <Select
        aria-label={`工具预算账户 ${bindingId}`}
        style={{width: '100%'}}
        value={value}
        disabled={disabled}
        placeholder="请选择工具费用账户"
        options={options.accounts.map((a) => ({
          value: a.budgetRef,
          label: `${a.budgetRef} · 可用 ${a.available} ${a.currency}`,
        }))}
        onChange={(ref) => onChange(ref, options.price)}
      />
      {!options.accounts.length && (
        <Alert
          type="warning"
          title="没有可用的工具费用账户，请管理员批准对应费率与空间预算，并初始化账户。"
        />
      )}
    </div>
  );
}
