import React, {useEffect, useId} from 'react';
import {Alert, Button, Card, Flex, InputNumber, Select, Table, Typography} from 'antd';
import {type AiModel, type BudgetPreset, type BudgetRateOption, type Money, refKey} from '@/services/platform/ai';

const rateName = (rate: BudgetPreset['rate']) => `${rate.id}@${rate.version}`;
const amountLabel = (value?: Money) => value ? `${value.amount} ${value.currency}` : '无规则';
const normalizedAmount = (value: Money['amount']) => String(value).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');

export default function WorkspaceAiBudgetRules({
                                                 options,
                                                 saved,
                                                 rules,
                                                 caps,
                                                 models,
                                                 associationIncomplete,
                                                 canRaise,
                                                 editable,
                                                 saving,
                                                 focus,
                                                 onFocus,
                                                 onChange,
                                                 onSave
                                               }: {
  options: BudgetRateOption[];
  saved: BudgetPreset[];
  rules: BudgetPreset[];
  caps: BudgetPreset[];
  models: AiModel[];
  associationIncomplete: boolean;
  canRaise: boolean;
  editable: boolean;
  saving: boolean;
  focus: { key: string } | null;
  onFocus: (key: string) => void;
  onChange: (rules: BudgetPreset[]) => void;
  onSave: () => void;
}) {
  const sectionId = useId();
  const anchorId = (key: string) => `${sectionId}-${encodeURIComponent(key)}`;
  const savedByRate = new Map(saved.map(rule => [refKey(rule.rate), rule]));
  const draftByRate = new Map(rules.map(rule => [refKey(rule.rate), rule]));
  const capByRate = new Map(caps.map(rule => [refKey(rule.rate), rule.limit]));
  const rows = options.filter(option => {
    const key = refKey(option.rate);
    return key === focus?.key || savedByRate.has(key) || draftByRate.has(key);
  });
  const changes = rows.flatMap(option => {
    const key = refKey(option.rate);
    const before = savedByRate.get(key)?.limit;
    const after = draftByRate.get(key)?.limit;
    if (!before && !after || before && after && normalizedAmount(before.amount) === normalizedAmount(after.amount)) return [];
    return [{key, rate: option.rate, before, after}];
  });
  useEffect(() => {
    if (!focus) return;
    const row = document.getElementById(`${sectionId}-${encodeURIComponent(focus.key)}`);
    row?.focus({preventScroll: true});
    row?.scrollIntoView?.({block: 'nearest'});
  }, [focus, sectionId]);

  return <Card size="small" title="预算规则">
    <Flex vertical gap={12}>
      <Typography.Text>规则按精确费率维护。同一主体在同一空间使用同费率模型时复用同一个兼容预算账户。表中额度按账户适用，各币种分别管理。</Typography.Text>
      <Select aria-label="查找预算费率" showSearch={{optionFilterProp: 'label'}}
              placeholder="查找费率并定位规则" value={focus?.key}
              options={options.map(option => ({
                value: refKey(option.rate),
                label: `${rateName(option.rate)} · ${option.currency} · ${option.kind === 'MODEL' ? '模型' : '工具'}`
              }))} onChange={onFocus}/>
      {associationIncomplete && <Alert type="info" title="部分已配置模型当前不可读，关联栏仅列出可核验的模型。"/>}
      <Table<BudgetRateOption> size="small" pagination={false} scroll={{x: 850}} rowKey={option => refKey(option.rate)}
                               dataSource={rows} locale={{emptyText: '暂无规则。可从启用模型表定位，或按费率查找。'}}
                               columns={[
                                 {
                                   title: '精确费率', key: 'rate', render: (_, option) =>
                                     <span tabIndex={-1} id={anchorId(refKey(option.rate))}>
            {rateName(option.rate)} · {option.currency}{focus?.key === refKey(option.rate) ? '（当前定位）' : ''}
          </span>
                                 },
                                 {
                                   title: '审核单价', key: 'price', render: (_, option) => option.kind === 'TOOL'
                                     ? `${option.toolPerCall ?? '—'} ${option.currency} / 次`
                                     : <>输入 {option.inputPerMillion ?? '—'}<br/>输出 {option.outputPerMillion ?? '—'} {option.currency} /
                                       百万 Token</>
                                 },
                                 {
                                   title: '关联的空间已配置模型', key: 'models', render: (_, option) => {
                                     if (option.kind === 'TOOL') return '工具费率';
                                     const linked = models.filter(model => refKey(model.rate) === refKey(option.rate));
                                     return linked.length ? linked.map(model =>
                                       <div key={refKey(model.binding)}>{model.name} · {model.binding.version}</div>
                                     ) : associationIncomplete ? '无可核验的关联模型' : '当前无关联模型';
                                   }
                                 },
                                 {
                                   title: '已批准额度',
                                   key: 'saved',
                                   render: (_, option) => amountLabel(savedByRate.get(refKey(option.rate))?.limit)
                                 },
                                 {
                                   title: '当前可信上限', key: 'cap', render: (_, option) =>
                                     amountLabel(capByRate.get(refKey(option.rate)) ?? {
                                       amount: '0',
                                       currency: option.currency
                                     })
                                 },
                                 {
                                   title: '规则草稿', key: 'draft', render: (_, option) => {
                                     const key = refKey(option.rate);
                                     const rule = draftByRate.get(key);
                                     if (!rule) return <Button disabled={!editable} onClick={() => {
                                       onChange([...rules, savedByRate.get(key) ?? {
                                         rate: option.rate,
                                         limit: {amount: '0', currency: option.currency}
                                       }]);
                                     }}>{savedByRate.has(key) ? '撤销移除' : '添加规则'}</Button>;
                                     return <Flex gap={8} wrap>
                                       <InputNumber aria-label={`预算额度 ${rateName(option.rate)}`} stringMode min="0"
                                                    max="1000000000"
                                                    disabled={!editable} value={String(rule.limit.amount)}
                                                    onChange={amount => onChange(rules.map(item => refKey(item.rate) === key
                                                      ? {
                                                        ...item,
                                                        limit: {...item.limit, amount: amount ?? '0'}
                                                      } : item))}/>
                                       <Button danger disabled={!editable}
                                               aria-label={`移除预算规则 ${rateName(option.rate)}`}
                                               onClick={() => onChange(rules.filter(item => refKey(item.rate) !== key))}>移除规则</Button>
                                     </Flex>;
                                   }
                                 },
                               ]}/>
      {changes.length > 0 && <section aria-label="预算规则变更预览">
        <Typography.Text strong>保存效果预览</Typography.Text>
        <ul>{changes.map(change => <li
          key={change.key}>{rateName(change.rate)} · {amountLabel(change.before)} → {amountLabel(change.after)}
          {!change.after ? '。该费率缺少规则，将阻止新的使用。'
            : Number(change.after.amount) === 0 ? '。规则额度为零，将阻止新的使用。'
              : '。同空间、同费率的已有账户沿用原账本并调整额度。'}</li>)}</ul>
      </section>}
      <Typography.Text
        type="secondary">规则变化保留历史账户、预留和已计费用。{canRaise ? '当前具有平台提额权限。' : '超出可信上限需要平台提额权限。'}</Typography.Text>
      <Button disabled={!editable} loading={saving} onClick={onSave}>保存独立预算规则</Button>
    </Flex>
  </Card>;
}
