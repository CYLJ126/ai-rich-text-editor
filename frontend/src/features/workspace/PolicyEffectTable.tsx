import React, {useEffect, useId} from 'react';
import {Alert, Card, Table, Typography} from 'antd';
import type {PolicyField, PolicyPreview, TenantPolicyView, WorkspacePolicyView} from '@/services/platform/policy';
import {policyFieldNames, policyValueText} from './policyForm';

export default function PolicyEffectTable({snapshot, ceiling, preview, focus, stale}: {
  snapshot: WorkspacePolicyView;
  ceiling?: TenantPolicyView | null;
  preview: PolicyPreview | null;
  focus?: { field: PolicyField };
  stale: boolean;
}) {
  const id = useId();
  const effect = stale ? undefined : snapshot.effect;
  const limit = !stale && ceiling?.policy.version === snapshot.tenantPolicyVersion ? ceiling.policy.rules : undefined;
  const current = snapshot.policy.rules;
  const fields = (Object.keys(policyFieldNames) as (PolicyField | 'membersMayCreateWorkspace')[])
    .filter(field => field !== 'membersMayCreateWorkspace');
  useEffect(() => {
    if (!focus) return;
    const target = document.getElementById(`${id}-${focus.field}`);
    target?.focus({preventScroll: true});
    target?.scrollIntoView?.({block: 'nearest'});
  }, [focus, id]);

  const result = (field: PolicyField, value?: typeof effect) => value ? <>
    {policyValueText(field, value.effective[field])}
    {value.limitedFields.includes(field) && <div>受租户上限限制</div>}
  </> : '未知';

  return <Card size="small" title="当前政策效果">
    {effect?.limitedFields.length ? <Alert type="warning"
                                           title="已保存的空间设置超出当前租户上限"
                                           description="生效值已按当前上限收紧。原空间设置仍保留，可在本空间设置中调整后保存。"/> : null}
    {!effect && <Alert type="info" title="当前政策效果未知，请重新读取政策。"/>}
    <Typography.Paragraph type="secondary">
      空间保存独立规则。下方仅比较两层政策，实际调用还会核验模型、凭据、预算及部署出口。
    </Typography.Paragraph>
    <Table<PolicyField> size="small" pagination={false} scroll={{x: true}} rowKey={field => field}
                        dataSource={fields} columns={[
      {
        title: '项目', key: 'field', render: (_, field) =>
          <span id={`${id}-${field}`} tabIndex={-1}>{policyFieldNames[field]}</span>
      },
      {
        title: '租户上限', key: 'limit', render: (_, field) => limit
          ? policyValueText(field, limit[field]) : '未读取完整上限'
      },
      {title: '本空间已保存设置', key: 'saved', render: (_, field) => policyValueText(field, current[field])},
      {title: '当前政策效果', key: 'effect', render: (_, field) => result(field, effect)},
      ...(preview && !stale ? [{
        title: '草稿政策效果', key: 'preview',
        render: (_: unknown, field: PolicyField) => result(field, preview.proposed)
      }] : []),
    ]}/>
  </Card>;
}
