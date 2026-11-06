import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Flex, Input, Popconfirm, Typography} from 'antd';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {AiApiError} from '@/services/arte-ai/request';
import {
  type EgressPolicy,
  type EgressPolicyUpdate,
  egressPolicyUpdate,
  getEgressPolicy,
  updateEgressPolicy,
} from '@/services/platform/modelEgress';
import {type BrowserContext, type Operation, readOperation, saveOperation} from './storage';

type Saved = { scope: BrowserContext['scope']; command: EgressPolicyUpdate };
const kind = 'model-egress-policy';

/**
 * 平台模型出口策略编辑器
 */
export default function ModelEgressPolicyEditor({userId, onSaved}: { userId: string; onSaved: () => void }) {
  const context = {userId, scope: {tenantId: 'control-admin', workspaceId: 'model-egress'}};
  const [restored] = useState(() => readOperation<Saved>(context, kind, ''));
  const [pending, setPending] = useState(restored.value);
  const [blocked, setBlocked] = useState(!!restored.warning);
  const [view, setView] = useState<EgressPolicy>();
  const [draft, setDraft] = useState(restored.value?.request.command.origins.join('\n') ?? '');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const reader = useRef<AbortController | null>(null);
  const writing = useRef(false);
  const live = useRef(false);
  const origins = draft.split('\n').map(line => line.trim()).filter(Boolean);
  const dirty = !!view && draft !== view.origins.join('\n');
  const request = view && egressPolicyUpdate.safeParse({expectedVersion: view.version, origins});
  const removed = view?.origins.filter(origin => !origins.includes(origin)) ?? [];

  useConfigurationDraft({
    group: 'egress', label: '平台出口策略',
    status: blocked ? 'blocked' : saving ? 'submitting' : pending ? 'pending' : dirty ? 'dirty' : 'clean',
    discard: () => {
      setDraft(view?.origins.join('\n') ?? '');
      return true;
    },
  });

  async function load(keepDraft = false) {
    reader.current?.abort();
    const read = new AbortController();
    reader.current = read;
    setLoading(true);
    setError('');
    try {
      const current = await getEgressPolicy({signal: read.signal});
      if (read.signal.aborted) return;
      setView(current);
      if (!keepDraft) setDraft(current.origins.join('\n'));
    } catch (failure) {
      if (read.signal.aborted) return;
      setView(undefined);
      setError(failure instanceof AiApiError && failure.httpStatus === 403
        ? '当前账号没有平台出口策略权限。' : '平台出口策略读取失败，请重试。');
    } finally {
      if (!read.signal.aborted) setLoading(false);
    }
  }

  useEffect(() => {
    live.current = true;
    void load(!!restored.value);
    return () => {
      live.current = false;
      reader.current?.abort();
    };
  }, []);

  async function save(operation: Operation<Saved>) {
    if (writing.current || blocked) return;
    if (!saveOperation(context, kind, '', operation)) {
      setBlocked(true);
      return;
    }
    writing.current = true;
    setPending(operation);
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await updateEgressPolicy(operation.request.command, operation.key);
      if (!live.current) return;
      setNotice(`策略版本 ${result.version} 已保存。`);
      onSaved();
      setPending(null);
      setBlocked(!saveOperation(context, kind, '', null));
      setDraft(result.origins.join('\n'));
      setView(undefined);
      await load();
    } catch (failure) {
      if (!live.current) return;
      if (failure instanceof AiApiError && (failure.httpStatus === 400
        || failure.httpStatus === 409 && failure.code !== 'PLATFORM_IDEMPOTENCY_CONFLICT')) {
        setPending(null);
        setBlocked(!saveOperation(context, kind, '', null));
        if (failure.httpStatus === 409) setView(undefined);
        setError(failure.httpStatus === 409 ? '策略版本已变更，请重新读取当前策略后再编辑。'
          : '保存失败，请检查 origin 格式及重复项。');
      } else {
        setError('保存结果暂未确认，请使用原操作重试。');
      }
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  const disabled = loading || saving || !!pending || blocked;
  return <Flex vertical gap={12} style={{padding: 16, height: '100%', overflowY: 'auto'}}>
    <Typography.Title level={4}>平台模型出口白名单</Typography.Title>
    <Typography.Paragraph>
      每行一个 origin，例如 https://api.deepseek.com。按协议、主机和端口精确匹配，最多 64 项。
      保存后，移除的地址将无法发起新的模型请求。空名单禁止所有模型出口。
    </Typography.Paragraph>
    {view &&
      <Typography.Text type="secondary">当前策略版本 {view.version} · {view.origins.length} 个 origin</Typography.Text>}
    {error && <Alert type="error" title={error}/>}
    {notice && <Alert type="success" title={notice}/>}
    {blocked && <Alert type="warning" title="本地操作记录不可用，请检查浏览器存储后重新打开页面。"/>}
    <Input.TextArea aria-label="模型出口 origin 白名单" value={draft} disabled={disabled || !view}
                    maxLength={64 * 2049} autoSize={{minRows: 6, maxRows: 12}}
                    placeholder="https://api.deepseek.com" onChange={event => setDraft(event.target.value)}/>
    {request && !request.success && <Alert type="warning" title="最多填写 64 项，每项最长 2048 个字符。"/>}
    <Flex gap={8}>
      <Button loading={loading} disabled={saving || !!pending} onClick={() => void load()}>
        {dirty ? '放弃草稿并重新读取' : '重新读取'}
      </Button>
      {pending ? <Button type="primary" disabled={blocked} loading={saving} onClick={() => void save(pending)}>
        重试确认原操作
      </Button> : <Popconfirm title="保存平台出口策略？"
                              description={origins.length === 0 ? '保存空名单会阻止所有新的模型请求。'
                                : removed.length ? `将移除 ${removed.length} 个当前 origin。` : '策略保存后立即用于新的模型请求。'}
                              okText="确认保存" cancelText="取消" disabled={disabled || !dirty || !request?.success}
                              onConfirm={() => {
                                if (request?.success) void save({
                                  key: crypto.randomUUID(), request: {scope: context.scope, command: request.data},
                                });
                              }}>
        <Button type="primary" disabled={disabled || !dirty || !request?.success}>保存策略</Button>
      </Popconfirm>}
    </Flex>
  </Flex>;
}
